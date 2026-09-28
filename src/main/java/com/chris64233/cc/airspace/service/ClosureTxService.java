package com.chris64233.cc.airspace.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.airspace.domain.AirSegment;
import com.chris64233.cc.airspace.domain.AirspaceClosure;
import com.chris64233.cc.airspace.domain.AirspaceClosureSegment;
import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.domain.ClosureImpactStatus;
import com.chris64233.cc.airspace.domain.ClosureStatus;
import com.chris64233.cc.airspace.domain.ClearanceStatus;
import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureSegmentRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.ClosureRequest;

/**
 * 关闭事件生命周期事务：创建（含受影响航班识别）、范围调整、取消。
 *
 * 加锁顺序为 关闭事件 -> 候选许可（主键升序）-> 待处理影响标记（主键升序），
 * 与许可变更事务的 许可 -> 航段 -> 影响标记 顺序兼容，不产生加锁环。
 * 关闭管理事务不写锁航段：关闭成员是新增关联，航段本体不被修改。
 */
@Service
public class ClosureTxService {

    private final AirspaceClosureRepository closureRepository;
    private final AirspaceClosureSegmentRepository closureSegmentRepository;
    private final ClosureImpactRepository impactRepository;
    private final FlightClearanceRepository clearanceRepository;
    private final AirSegmentRepository segmentRepository;
    private final Clock clock;

    public ClosureTxService(AirspaceClosureRepository closureRepository,
                            AirspaceClosureSegmentRepository closureSegmentRepository,
                            ClosureImpactRepository impactRepository,
                            FlightClearanceRepository clearanceRepository,
                            AirSegmentRepository segmentRepository,
                            Clock clock) {
        this.closureRepository = closureRepository;
        this.closureSegmentRepository = closureSegmentRepository;
        this.impactRepository = impactRepository;
        this.clearanceRepository = clearanceRepository;
        this.segmentRepository = segmentRepository;
        this.clock = clock;
    }

    /**
     * 创建关闭事件并立即识别受影响航班：
     * 仅标记在关闭时段内使用关闭航段、且尚未进入该航段（序号不小于已飞数）的 ACTIVE 航班；
     * 已进入或已飞完的航段不产生标记，不能反向修改。
     */
    @Transactional
    public AirspaceClosure create(ClosureRequest request) {
        List<AirSegment> segments = resolveSegments(request.segmentCodes());

        Instant now = Instant.now(clock);
        AirspaceClosure closure = new AirspaceClosure(request.eventNo(), request.reason(),
                request.startTime(), request.endTime(), now);
        for (AirSegment segment : segments) {
            closure.addSegment(segment);
        }
        AirspaceClosure saved = closureRepository.saveAndFlush(closure);

        List<FlightClearance> candidates = lockCandidates(segments, request.startTime(), request.endTime());
        Set<Long> segmentIds = idSet(segments);
        for (FlightClearance clearance : candidates) {
            markPendingImpacts(saved, clearance, segmentIds, Map.of());
        }
        return closureRepository.findDetailByEventNo(request.eventNo()).orElseThrow();
    }

    /**
     * 调整关闭范围（航段集合 / 时段）：范围版本 +1，旧关闭改道方案因版本过期被拒绝。
     * 已 HANDLED 的标记作为运行记录保留；PENDING 标记按新范围重算
     *（移出范围 -> RELEASED；航班已进入 -> EXPIRED；仍受影响 -> 刷新快照；新增 -> PENDING）。
     */
    @Transactional
    public AirspaceClosure reschedule(ClosureRequest request) {
        AirspaceClosure closure = closureRepository.findByEventNoForUpdate(request.eventNo())
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + request.eventNo()));
        if (closure.getStatus() != ClosureStatus.ACTIVE) {
            throw new StateConflictException("已取消的关闭事件不能调整范围：" + request.eventNo());
        }

        List<AirSegment> newSegments = resolveSegments(request.segmentCodes());
        Set<Long> newSegmentIds = idSet(newSegments);

        // 加锁顺序固定为 关闭事件 -> 许可（主键升序）-> 待处理标记，
        // 与关闭改道 / 普通改道 / 位置上报保持一致，避免跨关闭事件死锁。
        // 先无锁读取旧待处理标记，仅用于收集要锁定的许可；许可锁定后再重新加锁读取。
        List<ClosureImpact> pendingRead =
                impactRepository.findByClosureEventNoAndStatus(
                        request.eventNo(), ClosureImpactStatus.PENDING);

        // 候选许可 = 新范围时间窗内使用新/旧区域航段的 ACTIVE 航班 + 持有旧待处理标记的航班，
        // 合并去重后按主键升序一次性加锁，避免与改道 / 位置上报并发。
        Set<Long> oldSegmentIds = new HashSet<>();
        for (AirspaceClosureSegment member : closure.getSegments()) {
            oldSegmentIds.add(member.getSegment().getId());
        }
        Set<Long> unionSegmentIds = new HashSet<>(oldSegmentIds);
        unionSegmentIds.addAll(newSegmentIds);
        List<FlightClearance> candidates = clearanceRepository.findActiveCandidatesUsingSegments(
                ClearanceStatus.ACTIVE, request.startTime(), request.endTime(),
                new ArrayList<>(unionSegmentIds));
        Set<Long> lockIds = new HashSet<>(candidates.stream().map(FlightClearance::getId).toList());
        for (ClosureImpact impact : pendingRead) {
            lockIds.add(impact.getClearance().getId());
        }
        clearanceRepository.findAllByIdForUpdateOrderById(new ArrayList<>(lockIds.stream().sorted().toList()));

        // 许可锁定后再对该关闭的待处理标记加锁，此后标记集合在本事务内稳定
        List<ClosureImpact> existingPending =
                impactRepository.findPendingByClosureForUpdate(closure.getId());

        // 加锁后加载每个相关许可的最新航线明细
        Map<Long, FlightClearance> detailsById = new HashMap<>();
        for (Long id : lockIds) {
            FlightClearance locked = clearanceRepository.findById(id).orElseThrow();
            detailsById.put(id,
                    clearanceRepository.findDetailByExternalNo(locked.getExternalNo()).orElseThrow());
        }

        // 现有 PENDING 按 (许可, 航段序号) 索引，供重算时复用 / 失效
        Map<String, ClosureImpact> pendingIndex = new HashMap<>();
        for (ClosureImpact impact : existingPending) {
            pendingIndex.put(impactKey(impact.getClearance().getId(), impact.getSegmentOrder()), impact);
        }

        // 替换关闭成员、时间窗并提升范围版本：先让持久化上下文删除旧成员并 flush，
        // 再用物理删除兜底，保证新成员写入时 (closure_id, segment_id) 唯一约束上已无旧行。
        closure.clearSegments();
        closureRepository.saveAndFlush(closure);
        closureSegmentRepository.deleteAllByClosureId(closure.getId());
        closureSegmentRepository.flush();
        for (AirSegment segment : newSegments) {
            closure.addSegment(segment);
        }
        closure.reschedule(request.startTime(), request.endTime());
        closure.setReason(request.reason());
        closure.bumpScopeVersion();

        Set<String> desiredKeys = new HashSet<>();
        for (FlightClearance flight : detailsById.values()) {
            if (flight.getStatus() != ClearanceStatus.ACTIVE) {
                continue;
            }
            boolean windowOverlaps = flight.getStartTime().isBefore(request.endTime())
                    && flight.getEndTime().isAfter(request.startTime());
            for (SegmentReservation reservation : flight.getReservations()) {
                if (!windowOverlaps || !newSegmentIds.contains(reservation.getSegment().getId())) {
                    continue;
                }
                String key = impactKey(flight.getId(), reservation.getSegmentOrder());
                ClosureImpact existing = pendingIndex.get(key);
                if (reservation.getSegmentOrder() < flight.getFlownLegCount()) {
                    // 识别时尚未进入、现在已进入：旧标记过期，且不再新建
                    if (existing != null) {
                        existing.markExpired();
                    }
                    continue;
                }
                desiredKeys.add(key);
                if (existing == null) {
                    impactRepository.save(new ClosureImpact(closure, flight,
                            reservation.getSegmentOrder(), reservation.getSegment().getId(),
                            flight.getVersion(), flight.getFlownLegCount()));
                } else {
                    existing.retarget(reservation.getSegmentOrder(), reservation.getSegment().getId(),
                            flight.getVersion(), flight.getFlownLegCount());
                }
            }
        }

        // 不再受新范围覆盖的 PENDING：因范围移出而解除；若实际已飞入则记为过期
        for (ClosureImpact impact : pendingIndex.values()) {
            String key = impactKey(impact.getClearance().getId(), impact.getSegmentOrder());
            if (desiredKeys.contains(key)) {
                continue;
            }
            FlightClearance flight = detailsById.get(impact.getClearance().getId());
            if (flight != null && impact.getSegmentOrder() < flight.getFlownLegCount()) {
                impact.markExpired();
            } else {
                impact.markReleased();
            }
        }

        closureRepository.saveAndFlush(closure);
        return closureRepository.findDetailByEventNo(request.eventNo()).orElseThrow();
    }

    /**
     * 取消关闭：只把尚未处理航班的待处理标记解除（RELEASED），
     * 已完成的关闭改道（HANDLED 标记、航线版本、占用事件）不自动撤销。
     * 重复取消幂等。
     */
    @Transactional
    public AirspaceClosure cancel(String eventNo) {
        AirspaceClosure closure = closureRepository.findByEventNoForUpdate(eventNo).orElse(null);
        if (closure == null) {
            return null;
        }
        if (closure.getStatus() == ClosureStatus.CANCELLED) {
            return closureRepository.findDetailByEventNo(eventNo).orElseThrow();
        }
        Instant now = Instant.now(clock);
        // 已持有关闭事件行写锁（与范围调整、关闭改道串行化），状态校验也已完成，直接置位即可
        closure.markCancelled(now);
        closureRepository.saveAndFlush(closure);
        for (ClosureImpact impact : impactRepository.findPendingByClosureForUpdate(closure.getId())) {
            impact.markReleased();
        }
        impactRepository.flush();
        return closureRepository.findDetailByEventNo(eventNo).orElseThrow();
    }

    // ------------------------------------------------------------------

    /**
     * 锁定候选许可并返回最新明细。先按主键升序取许可写锁（与改道 / 上报互斥），
     * 再逐个加载占用明细，保证标记基于最新航线与飞行位置。
     */
    private List<FlightClearance> lockCandidates(List<AirSegment> segments, Instant startTime, Instant endTime) {
        return lockCandidates(idSet(segments), startTime, endTime);
    }

    private List<FlightClearance> lockCandidates(Set<Long> segmentIds, Instant startTime, Instant endTime) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        List<FlightClearance> candidates = clearanceRepository.findActiveCandidatesUsingSegments(
                ClearanceStatus.ACTIVE, startTime, endTime, new ArrayList<>(segmentIds));
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> ids = candidates.stream().map(FlightClearance::getId).distinct().sorted().toList();
        clearanceRepository.findAllByIdForUpdateOrderById(ids);
        List<FlightClearance> details = new ArrayList<>();
        for (Long id : ids) {
            FlightClearance candidate = candidates.stream()
                    .filter(c -> c.getId().equals(id)).findFirst().orElseThrow();
            details.add(clearanceRepository.findDetailByExternalNo(candidate.getExternalNo()).orElseThrow());
        }
        return details;
    }

    /**
     * 为单个航班写入 PENDING 标记：跳过已进入 / 已飞完航段，已存在的标记不重复写入。
     */
    private void markPendingImpacts(AirspaceClosure closure, FlightClearance clearance,
                                    Set<Long> closedSegmentIds,
                                    Map<String, ClosureImpact> existing) {
        for (SegmentReservation reservation : clearance.getReservations()) {
            if (!closedSegmentIds.contains(reservation.getSegment().getId())) {
                continue;
            }
            if (reservation.getSegmentOrder() < clearance.getFlownLegCount()) {
                continue;
            }
            String key = impactKey(clearance.getId(), reservation.getSegmentOrder());
            if (existing.containsKey(key)) {
                continue;
            }
            impactRepository.save(new ClosureImpact(closure, clearance,
                    reservation.getSegmentOrder(), reservation.getSegment().getId(),
                    clearance.getVersion(), clearance.getFlownLegCount()));
        }
    }

    private List<AirSegment> resolveSegments(List<String> codes) {
        Set<String> seen = new HashSet<>();
        List<AirSegment> segments = new ArrayList<>();
        for (String code : codes) {
            if (!seen.add(code)) {
                throw new ParamInvalidException("关闭区域中存在重复航段：" + code);
            }
            AirSegment segment = segmentRepository.findByCode(code)
                    .orElseThrow(() -> new ParamInvalidException("航段不存在：" + code));
            segments.add(segment);
        }
        return segments;
    }

    private Set<Long> idSet(List<AirSegment> segments) {
        Set<Long> ids = new HashSet<>();
        for (AirSegment segment : segments) {
            ids.add(segment.getId());
        }
        return ids;
    }

    private String impactKey(Long clearanceId, int segmentOrder) {
        return clearanceId + "#" + segmentOrder;
    }
}
