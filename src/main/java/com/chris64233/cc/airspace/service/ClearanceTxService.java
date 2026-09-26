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
import com.chris64233.cc.airspace.domain.ClearanceStatus;
import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.OccupancyEventType;
import com.chris64233.cc.airspace.domain.RouteVersion;
import com.chris64233.cc.airspace.domain.SegmentOccupancyEvent;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.repo.SegmentOccupancyEventRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.error.CapacityExceededException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.RerouteRequest;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;

/**
 * 承载真正的数据库事务：航段行锁、容量判定、条件取消、改道换线均在数据库事务内完成，
 * 不依赖 JVM 内存状态，保证进程重启与并发下的正确性。
 */
@Service
public class ClearanceTxService {

    private final AirSegmentRepository segmentRepository;
    private final FlightClearanceRepository clearanceRepository;
    private final SegmentReservationRepository reservationRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final SegmentOccupancyEventRepository occupancyEventRepository;
    private final Clock clock;

    public ClearanceTxService(AirSegmentRepository segmentRepository,
                              FlightClearanceRepository clearanceRepository,
                              SegmentReservationRepository reservationRepository,
                              RouteVersionRepository routeVersionRepository,
                              SegmentOccupancyEventRepository occupancyEventRepository,
                              Clock clock) {
        this.segmentRepository = segmentRepository;
        this.clearanceRepository = clearanceRepository;
        this.reservationRepository = reservationRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.occupancyEventRepository = occupancyEventRepository;
        this.clock = clock;
    }

    /**
     * 原子提交：统一顺序加锁全部航段 -> 逐段判定容量 -> 一次性写入许可与全部占用，
     * 并记录初始航线版本（v1）与逐航段 ACQUIRE 事件。
     * 容量不足抛 {@link CapacityExceededException}，事务整体回滚，不会留下部分占用。
     * 外部申请号唯一约束冲突抛出 DataIntegrityViolationException，由门面层处理幂等。
     */
    @Transactional
    public FlightClearance submit(SubmitClearanceRequest request) {
        List<LegInput> legs = resolveAndLockLegs(request.legs());

        for (LegInput leg : legs) {
            long overlapping = reservationRepository.countActiveOverlaps(
                    leg.segment().getId(), request.startTime(), request.endTime());
            if (overlapping + 1 > leg.segment().getCapacity()) {
                throw new CapacityExceededException("航段 " + leg.segment().getCode()
                        + " 在申请时间区间内剩余容量不足：已占用 " + overlapping
                        + "，容量 " + leg.segment().getCapacity());
            }
        }

        Instant now = Instant.now(clock);
        FlightClearance clearance = new FlightClearance(request.externalNo(), request.aircraft(),
                request.startTime(), request.endTime(), ClearanceStatus.ACTIVE, now);
        for (LegInput leg : legs) {
            clearance.addReservation(leg.order(), leg.segment(), leg.altitude());
        }
        FlightClearance saved = clearanceRepository.saveAndFlush(clearance);

        RouteVersion initial = new RouteVersion(saved, saved.getVersion(), null, 0,
                request.startTime(), now);
        for (LegInput leg : legs) {
            initial.addLeg(leg.order(), leg.segment(), leg.altitude());
        }
        routeVersionRepository.save(initial);
        for (LegInput leg : legs) {
            recordEvent(saved, leg.segment(), OccupancyEventType.ACQUIRE, leg.altitude(),
                    saved.getVersion(), null, now);
        }
        return saved;
    }

    /**
     * 原子取消：许可行锁（与改道、位置上报串行化）-> 按统一顺序锁定涉及航段（与提交互斥）
     * -> 校验是否已到开始时间 -> 条件更新保证 ACTIVE -> CANCELLED 只生效一次，
     * 并记录全部航段的 RELEASE 事件。
     */
    @Transactional
    public CancelOutcome cancel(String externalNo) {
        if (clearanceRepository.findByExternalNoForUpdate(externalNo).isEmpty()) {
            return CancelOutcome.notFound();
        }
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        if (clearance.getStatus() == ClearanceStatus.CANCELLED) {
            return CancelOutcome.done(clearance);
        }
        if (clearance.getStatus() == ClearanceStatus.COMPLETED) {
            throw new StateConflictException("已完成的许可不能取消：" + externalNo);
        }

        List<Long> segmentIds = clearance.getReservations().stream()
                .map(r -> r.getSegment().getId())
                .sorted()
                .toList();
        segmentRepository.findAllByIdForUpdateOrderById(segmentIds);

        if (!clearance.getStartTime().isAfter(Instant.now(clock))) {
            throw new StateConflictException("已到达飞行开始时间，许可不得取消：" + externalNo);
        }

        Instant now = Instant.now(clock);
        clearanceRepository.cancelIfActive(externalNo,
                ClearanceStatus.ACTIVE, ClearanceStatus.CANCELLED, now);
        for (SegmentReservation reservation : clearance.getReservations()) {
            recordEvent(clearance, reservation.getSegment(), OccupancyEventType.RELEASE,
                    reservation.getAltitude(), clearance.getVersion(), null, now);
        }
        FlightClearance refreshed = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        return CancelOutcome.done(refreshed);
    }

    /**
     * 原子改道：许可行锁串行化并发变更 -> 状态、版本、衔接位置校验
     * -> 统一顺序加锁新旧航段 -> 先取得全部新航段容量 -> 再释放不再使用的旧航段。
     * 任一校验或容量判定失败都抛异常整体回滚，原许可与原占用保持不变。
     */
    @Transactional
    public FlightClearance reroute(String externalNo, RerouteRequest request) {
        clearanceRepository.findByExternalNoForUpdate(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();

        if (clearance.getStatus() == ClearanceStatus.CANCELLED) {
            throw new StateConflictException("已取消的许可不能改道：" + externalNo);
        }
        if (clearance.getStatus() == ClearanceStatus.COMPLETED) {
            throw new StateConflictException("已完成的许可不能改道：" + externalNo);
        }
        Instant now = Instant.now(clock);
        if (!clearance.getEndTime().isAfter(now)) {
            throw new StateConflictException("许可已过结束时间，不能改道：" + externalNo);
        }
        if (clearance.getVersion() != request.expectedVersion()) {
            throw new StateConflictException("许可版本已变化：请求基于版本 " + request.expectedVersion()
                    + "，当前版本 " + clearance.getVersion());
        }

        List<SegmentReservation> current = clearance.getReservations();
        int from = request.fromLegIndex();
        if (from < 0 || from >= current.size()) {
            throw new ParamInvalidException("改道起点航段序号超出范围：" + from
                    + "（当前航线共 " + current.size() + " 段）");
        }
        if (from < clearance.getFlownLegCount()) {
            throw new StateConflictException("改道起点 " + from + " 早于当前飞行位置 "
                    + clearance.getFlownLegCount() + "，已飞过的航段不可改写");
        }
        if (request.effectiveAt().isBefore(now)) {
            throw new ParamInvalidException("生效时间不能早于当前时间");
        }
        if (!request.effectiveAt().isBefore(clearance.getEndTime())) {
            throw new ParamInvalidException("生效时间必须早于许可结束时间");
        }

        // 保留前缀：[0, from) 的航段原样保留，新航段不得与之重复
        List<SegmentReservation> keptPrefix = new ArrayList<>(current.subList(0, from));
        List<SegmentReservation> oldTail = new ArrayList<>(current.subList(from, current.size()));
        Set<String> keptCodes = new HashSet<>();
        for (SegmentReservation kept : keptPrefix) {
            keptCodes.add(kept.getSegment().getCode());
        }
        for (SubmitClearanceRequest.LegRequest leg : request.legs()) {
            if (keptCodes.contains(leg.segmentCode())) {
                throw new ParamInvalidException("新航线与保留航段重复：" + leg.segmentCode());
            }
        }

        // 统一顺序一次性加锁新旧航段（与提交、取消互斥），再逐个判定高度与容量
        Set<Long> oldTailSegmentIds = new HashSet<>();
        for (SegmentReservation reservation : oldTail) {
            oldTailSegmentIds.add(reservation.getSegment().getId());
        }
        List<LegInput> newLegs = resolveAndLockLegs(request.legs(), oldTailSegmentIds);

        // 先原子取得全部新航段容量：判定基于 [生效时间, 许可结束时间)，排除自身已有占用
        for (LegInput leg : newLegs) {
            long overlapping = reservationRepository.countActiveOverlapsExcluding(
                    leg.segment().getId(), request.effectiveAt(), clearance.getEndTime(), clearance.getId());
            if (overlapping + 1 > leg.segment().getCapacity()) {
                throw new CapacityExceededException("航段 " + leg.segment().getCode()
                        + " 在改道生效时间区间内剩余容量不足：已占用 " + overlapping
                        + "，容量 " + leg.segment().getCapacity());
            }
        }

        // 容量全部就绪后换线：删除旧尾部占用（先 flush 释放唯一约束），再写入新航段
        clearance.getReservations().removeAll(oldTail);
        reservationRepository.deleteAll(oldTail);
        reservationRepository.flush();
        for (LegInput leg : newLegs) {
            clearance.addReservation(from + leg.order(), leg.segment(), leg.altitude());
        }

        // 再释放不再使用的旧航段：事件按先 ACQUIRE 后 RELEASE 记录净变化
        int newVersion = clearance.getVersion() + 1;
        Set<Long> newSegmentIds = new HashSet<>();
        for (LegInput leg : newLegs) {
            newSegmentIds.add(leg.segment().getId());
        }
        for (LegInput leg : newLegs) {
            if (!oldTailSegmentIds.contains(leg.segment().getId())) {
                recordEvent(clearance, leg.segment(), OccupancyEventType.ACQUIRE, leg.altitude(),
                        newVersion, request.rerouteNo(), now);
            }
        }
        for (SegmentReservation reservation : oldTail) {
            if (!newSegmentIds.contains(reservation.getSegment().getId())) {
                recordEvent(clearance, reservation.getSegment(), OccupancyEventType.RELEASE,
                        reservation.getAltitude(), newVersion, request.rerouteNo(), now);
            }
        }

        // 航线历史快照：完整新航线 = 保留前缀 + 新航段
        RouteVersion routeVersion = new RouteVersion(clearance, newVersion, request.rerouteNo(),
                from, request.effectiveAt(), now);
        for (SegmentReservation kept : keptPrefix) {
            routeVersion.addLeg(kept.getSegmentOrder(), kept.getSegment(), kept.getAltitude());
        }
        for (LegInput leg : newLegs) {
            routeVersion.addLeg(from + leg.order(), leg.segment(), leg.altitude());
        }
        routeVersionRepository.save(routeVersion);

        clearance.bumpVersion();
        clearanceRepository.saveAndFlush(clearance);
        return clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
    }

    /**
     * 飞行开始：标记起飞时间。重复开始返回原状态（幂等）；
     * 已取消或已完成的许可不能开始。
     */
    @Transactional
    public FlightClearance start(String externalNo) {
        FlightClearance clearance = clearanceRepository.findByExternalNoForUpdate(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        if (clearance.getStatus() != ClearanceStatus.ACTIVE) {
            throw new StateConflictException("已取消或已完成的许可不能开始飞行：" + externalNo);
        }
        if (clearance.getStartedAt() == null) {
            clearance.markStarted(Instant.now(clock));
            clearanceRepository.saveAndFlush(clearance);
        }
        return clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
    }

    /**
     * 位置上报：推进已飞过航段数。基于旧位置的上报直接忽略（不回退）；
     * 全部航段飞完后许可置为 COMPLETED。
     */
    @Transactional
    public FlightClearance reportProgress(String externalNo, int flownLegCount) {
        clearanceRepository.findByExternalNoForUpdate(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        if (clearance.getStatus() != ClearanceStatus.ACTIVE) {
            throw new StateConflictException("已取消或已完成的许可不能上报位置：" + externalNo);
        }
        if (flownLegCount < 0 || flownLegCount > clearance.getReservations().size()) {
            throw new ParamInvalidException("已飞过航段数超出范围：" + flownLegCount);
        }
        if (flownLegCount <= clearance.getFlownLegCount()) {
            return clearance;
        }
        if (clearance.getStartedAt() == null) {
            clearance.markStarted(Instant.now(clock));
        }
        clearance.advanceFlownLegCount(flownLegCount);
        if (flownLegCount == clearance.getReservations().size()) {
            clearance.markCompleted();
        }
        clearanceRepository.saveAndFlush(clearance);
        return clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
    }

    /**
     * 解析航段并加锁：先按代码解析，再把申请航段与 extraLockIds 合并，
     * 按主键升序一次性加锁（所有事务同一顺序，避免死锁），最后校验重复航段与高度范围。
     */
    private List<LegInput> resolveAndLockLegs(List<SubmitClearanceRequest.LegRequest> legs) {
        return resolveAndLockLegs(legs, Set.of());
    }

    private List<LegInput> resolveAndLockLegs(List<SubmitClearanceRequest.LegRequest> legs,
                                              Set<Long> extraLockIds) {
        if (legs == null || legs.isEmpty()) {
            throw new ParamInvalidException("航段列表不能为空");
        }
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        List<Long> segmentIds = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            SubmitClearanceRequest.LegRequest leg = legs.get(i);
            Integer previous = firstIndex.putIfAbsent(leg.segmentCode(), i);
            if (previous != null) {
                throw new ParamInvalidException(
                        "航段顺序中存在重复航段：" + leg.segmentCode());
            }
        }

        List<AirSegment> byCode = new ArrayList<>();
        for (SubmitClearanceRequest.LegRequest leg : legs) {
            AirSegment segment = segmentRepository.findByCode(leg.segmentCode())
                    .orElseThrow(() -> new ParamInvalidException(
                            "航段不存在：" + leg.segmentCode()));
            byCode.add(segment);
            segmentIds.add(segment.getId());
        }

        Set<Long> allIds = new HashSet<>(segmentIds);
        allIds.addAll(extraLockIds);
        List<Long> sortedIds = allIds.stream().sorted().toList();
        List<AirSegment> locked = segmentRepository.findAllByIdForUpdateOrderById(sortedIds);
        if (locked.size() != sortedIds.size()) {
            throw new ParamInvalidException("部分航段不存在，无法加锁");
        }
        Map<Long, AirSegment> lockedById = new LinkedHashMap<>();
        for (AirSegment segment : locked) {
            lockedById.put(segment.getId(), segment);
        }

        List<LegInput> result = new ArrayList<>();
        for (int i = 0; i < legs.size(); i++) {
            AirSegment segment = lockedById.get(byCode.get(i).getId());
            int altitude = legs.get(i).altitude();
            if (altitude < segment.getMinAltitude() || altitude > segment.getMaxAltitude()) {
                throw new ParamInvalidException("航段 " + segment.getCode() + " 的飞行高度 "
                        + altitude + " 超出允许范围 [" + segment.getMinAltitude() + ", "
                        + segment.getMaxAltitude() + "]");
            }
            result.add(new LegInput(i, segment, altitude));
        }
        return result;
    }

    private void recordEvent(FlightClearance clearance, AirSegment segment, OccupancyEventType type,
                             int altitude, int routeVersion, String rerouteNo, Instant now) {
        occupancyEventRepository.save(new SegmentOccupancyEvent(
                clearance, segment, type, altitude, routeVersion, rerouteNo, now));
    }

    private record LegInput(int order, AirSegment segment, int altitude) {
    }

    public record CancelOutcome(boolean found, FlightClearance clearance) {

        static CancelOutcome notFound() {
            return new CancelOutcome(false, null);
        }

        static CancelOutcome done(FlightClearance clearance) {
            return new CancelOutcome(true, clearance);
        }
    }
}
