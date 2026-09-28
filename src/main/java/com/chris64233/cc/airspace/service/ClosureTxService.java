package com.chris64233.cc.airspace.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.airspace.domain.AirSegment;
import com.chris64233.cc.airspace.domain.AirspaceClosure;
import com.chris64233.cc.airspace.domain.ClearanceStatus;
import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.domain.ClosureImpactStatus;
import com.chris64233.cc.airspace.domain.ClosureStatus;
import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.CreateClosureRequest;
import com.chris64233.cc.airspace.web.dto.ReviseClosureRequest;

/**
 * 临时空域关闭事件的数据库事务：创建（含受影响航段标记）、范围修订、取消。
 * 加锁严格遵循全局层次：关闭事件行(level0) -&gt; 航段行(level1) -&gt; 许可行(level2)，
 * 同层资源一律按主键升序加锁。加锁前的探测只读标量，避免实体进入一级缓存后
 * 覆盖持锁重读的最新状态。
 */
@Service
public class ClosureTxService {

    private final AirspaceClosureRepository closureRepository;
    private final AirSegmentRepository segmentRepository;
    private final FlightClearanceRepository clearanceRepository;
    private final ClosureImpactRepository impactRepository;
    private final Clock clock;

    public ClosureTxService(AirspaceClosureRepository closureRepository,
                            AirSegmentRepository segmentRepository,
                            FlightClearanceRepository clearanceRepository,
                            ClosureImpactRepository impactRepository,
                            Clock clock) {
        this.closureRepository = closureRepository;
        this.segmentRepository = segmentRepository;
        this.clearanceRepository = clearanceRepository;
        this.impactRepository = impactRepository;
        this.clock = clock;
    }

    /**
     * 创建关闭事件：先锁定时间窗 / 区域重叠的既有有效关闭事件（level0），
     * 再锁定区域内全部航段（level1），写入事件后扫描时间窗重叠的有效许可，
     * 按主键升序锁定许可（level2）并标记尚未进入的受影响航段。
     * 已进入（航段序号 &lt; flownLegCount）或已飞完、已取消 / 完成的航段不标记，不可反向修改。
     */
    @Transactional
    public AirspaceClosure create(CreateClosureRequest request) {
        List<Long> areaIds = resolveSegmentIds(request.segmentCodes());

        lockOverlappingClosures(areaIds, request.startTime(), request.endTime(), null);
        List<AirSegment> areaSegments = lockSegmentsInRequestOrder(request.segmentCodes(), areaIds);

        Instant now = Instant.now(clock);
        AirspaceClosure closure = new AirspaceClosure(request.closureNo(), request.reason(),
                request.startTime(), request.endTime(), now);
        for (AirSegment segment : areaSegments) {
            closure.addSegment(segment);
        }
        AirspaceClosure saved = closureRepository.saveAndFlush(closure);

        markImpacts(saved, areaIds, request.startTime(), request.endTime(), now);
        return closureRepository.findDetailByClosureNo(request.closureNo()).orElseThrow();
    }

    /**
     * 修订关闭范围 / 时段：按主键升序锁定本事件与区域 / 时段重叠的其他有效关闭事件（level0）
     * -&gt; 锁定新区域航段（level1）-&gt; 删除全部 PENDING 标记（已 REROUTED 的运行记录保留）
     * -&gt; 范围版本 +1、替换区域成员 -&gt; 按新范围重扫并标记。
     * 已取消的关闭事件不能修订。
     */
    @Transactional
    public AirspaceClosure revise(String closureNo, ReviseClosureRequest request) {
        Long selfId = closureRepository.findIdByClosureNo(closureNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + closureNo));
        List<Long> areaIds = resolveSegmentIds(request.segmentCodes());

        lockOverlappingClosures(areaIds, request.startTime(), request.endTime(), selfId);
        AirspaceClosure closure = closureRepository
                .findAllByIdForUpdateOrderById(List.of(selfId)).stream().findFirst().orElseThrow();
        if (closure.getStatus() == ClosureStatus.CANCELLED) {
            throw new StateConflictException("已取消的关闭事件不能修订：" + closureNo);
        }
        List<AirSegment> areaSegments = lockSegmentsInRequestOrder(request.segmentCodes(), areaIds);

        impactRepository.deletePendingByClosure(closure.getId(), ClosureImpactStatus.PENDING);
        impactRepository.flush();

        // 先 flush 旧区域成员的删除（Hibernate 默认先 INSERT 后 DELETE，
        // 新旧区域共有的航段会撞唯一约束），再写入新成员
        closure.getSegments().clear();
        closureRepository.saveAndFlush(closure);
        for (AirSegment segment : areaSegments) {
            closure.addSegment(segment);
        }
        closure.revise(request.reason(), request.startTime(), request.endTime());
        closureRepository.saveAndFlush(closure);

        Instant now = Instant.now(clock);
        markImpacts(closure, areaIds, request.startTime(), request.endTime(), now);
        return closureRepository.findDetailByClosureNo(closureNo).orElseThrow();
    }

    /**
     * 取消关闭事件：行锁后删除全部 PENDING 标记（只解除尚未处理航班的限制），
     * 已 REROUTED 的改道结果保留，不自动撤销。重复取消幂等。
     */
    @Transactional
    public AirspaceClosure cancel(String closureNo) {
        Long selfId = closureRepository.findIdByClosureNo(closureNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + closureNo));
        AirspaceClosure closure = closureRepository
                .findAllByIdForUpdateOrderById(List.of(selfId)).stream().findFirst().orElseThrow();
        if (closure.getStatus() == ClosureStatus.CANCELLED) {
            return closureRepository.findDetailByClosureNo(closureNo).orElseThrow();
        }
        impactRepository.deletePendingByClosure(closure.getId(), ClosureImpactStatus.PENDING);
        closure.markCancelled(Instant.now(clock));
        closureRepository.saveAndFlush(closure);
        return closureRepository.findDetailByClosureNo(closureNo).orElseThrow();
    }

    /**
     * 锁定与给定区域 + 时间窗重叠的有效关闭事件，按主键升序加写锁（level0）。
     * includeClosureId 用于修订时把自身（数据库成员尚为旧区域）一并纳入锁定集合。
     * 此时尚未持有任何航段 / 许可锁，等待期间不会造成锁序倒置。
     */
    private void lockOverlappingClosures(List<Long> areaIds, Instant windowStart, Instant windowEnd,
                                         Long includeClosureId) {
        Set<Long> closureIds = new HashSet<>(closureRepository
                .findActiveForSegmentsAndWindow(areaIds, windowStart, windowEnd).stream()
                .map(AirspaceClosure::getId)
                .toList());
        if (includeClosureId != null) {
            closureIds.add(includeClosureId);
        }
        if (!closureIds.isEmpty()) {
            closureRepository.findAllByIdForUpdateOrderById(closureIds.stream().sorted().toList());
        }
    }

    /**
     * 按主键升序锁定区域航段（level1），返回顺序与请求代码顺序一致。
     */
    private List<AirSegment> lockSegmentsInRequestOrder(List<String> segmentCodes, List<Long> areaIds) {
        List<Long> sortedIds = areaIds.stream().sorted().toList();
        List<AirSegment> locked = segmentRepository.findAllByIdForUpdateOrderById(sortedIds);
        if (locked.size() != sortedIds.size()) {
            throw new ParamInvalidException("部分航段不存在，无法加锁");
        }
        List<AirSegment> ordered = new ArrayList<>();
        for (String code : segmentCodes) {
            ordered.add(segmentRepository.findByCode(code).orElseThrow());
        }
        return ordered;
    }

    /**
     * 扫描并标记受影响航段：时间窗与关闭时段重叠的有效许可，按主键升序加许可写锁后逐航段判定。
     * 只标记许可当前尚未进入（segmentOrder &gt;= flownLegCount）的区域航段；
     * 标记携带当前关闭范围版本，供改道方案识别旧方案。
     */
    private void markImpacts(AirspaceClosure closure, List<Long> areaIds,
                             Instant windowStart, Instant windowEnd, Instant now) {
        List<Long> candidateIds = clearanceRepository.findActiveIdsOnSegments(
                areaIds.stream().sorted().toList(), windowStart, windowEnd);
        if (candidateIds.isEmpty()) {
            return;
        }
        clearanceRepository.findAllByIdForUpdateOrderById(candidateIds.stream().sorted().toList());

        Set<Long> areaIdSet = new HashSet<>(areaIds);
        for (Long candidateId : candidateIds) {
            FlightClearance detail = clearanceRepository.findById(candidateId).orElseThrow();
            if (detail.getStatus() != ClearanceStatus.ACTIVE) {
                continue;
            }
            for (SegmentReservation reservation : detail.getReservations()) {
                if (reservation.getSegmentOrder() < detail.getFlownLegCount()) {
                    continue;
                }
                if (!areaIdSet.contains(reservation.getSegment().getId())) {
                    continue;
                }
                impactRepository.save(new ClosureImpact(closure, detail, reservation.getSegment(),
                        reservation.getSegmentOrder(), closure.getScopeVersion(), now));
            }
        }
    }

    /**
     * 解析区域航段代码为主键列表（标量探测）：去重、存在性校验，返回顺序与请求一致。
     */
    private List<Long> resolveSegmentIds(List<String> segmentCodes) {
        if (segmentCodes == null || segmentCodes.isEmpty()) {
            throw new ParamInvalidException("关闭区域航段列表不能为空");
        }
        Set<String> uniqueCodes = new HashSet<>();
        List<Long> ids = new ArrayList<>();
        for (String code : segmentCodes) {
            if (!uniqueCodes.add(code)) {
                throw new ParamInvalidException("关闭区域中存在重复航段：" + code);
            }
            ids.add(segmentRepository.findIdByCode(code)
                    .orElseThrow(() -> new ParamInvalidException("航段不存在：" + code)));
        }
        return ids;
    }
}
