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
import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.domain.ClosureStatus;
import com.chris64233.cc.airspace.domain.ClearanceStatus;
import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.OccupancyEventType;
import com.chris64233.cc.airspace.domain.RouteVersion;
import com.chris64233.cc.airspace.domain.SegmentOccupancyEvent;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureSegmentRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.repo.SegmentOccupancyEventRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.error.CapacityExceededException;
import com.chris64233.cc.airspace.service.error.ClosureConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.ClosureRerouteRequest;
import com.chris64233.cc.airspace.web.dto.RerouteRequest;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;

/**
 * 承载真正的数据库事务：航段行锁、容量判定、条件取消、改道换线均在数据库事务内完成，
 * 不依赖 JVM 内存状态，保证进程重启与并发下的正确性。
 *
 * 空域关闭联动：提交与改道均拒绝进入生效中关闭区域的航线；改道确认替代航线时在同一
 * 事务内完成新旧容量切换并同步处理关闭影响标记；位置上报推进时把航班已进入的待处理
 * 标记置为 EXPIRED（已进入航段不能反向修改）。
 */
@Service
public class ClearanceTxService {

    private final AirSegmentRepository segmentRepository;
    private final AirspaceClosureRepository closureRepository;
    private final AirspaceClosureSegmentRepository closureSegmentRepository;
    private final ClosureImpactRepository impactRepository;
    private final FlightClearanceRepository clearanceRepository;
    private final SegmentReservationRepository reservationRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final SegmentOccupancyEventRepository occupancyEventRepository;
    private final Clock clock;

    public ClearanceTxService(AirSegmentRepository segmentRepository,
                              AirspaceClosureRepository closureRepository,
                              AirspaceClosureSegmentRepository closureSegmentRepository,
                              ClosureImpactRepository impactRepository,
                              FlightClearanceRepository clearanceRepository,
                              SegmentReservationRepository reservationRepository,
                              RouteVersionRepository routeVersionRepository,
                              SegmentOccupancyEventRepository occupancyEventRepository,
                              Clock clock) {
        this.segmentRepository = segmentRepository;
        this.closureRepository = closureRepository;
        this.closureSegmentRepository = closureSegmentRepository;
        this.impactRepository = impactRepository;
        this.clearanceRepository = clearanceRepository;
        this.reservationRepository = reservationRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.occupancyEventRepository = occupancyEventRepository;
        this.clock = clock;
    }

    /**
     * 原子提交：统一顺序加锁全部航段 -> 逐段判定关闭区域与容量 -> 一次性写入许可与全部占用，
     * 并记录初始航线版本（v1）与逐航段 ACQUIRE 事件。
     * 航段处于生效中的关闭区域或容量不足时抛异常，事务整体回滚，不会留下部分占用。
     * 外部申请号唯一约束冲突抛出 DataIntegrityViolationException，由门面层处理幂等。
     */
    @Transactional
    public FlightClearance submit(SubmitClearanceRequest request) {
        List<LegInput> legs = resolveAndLockLegs(request.legs());

        for (LegInput leg : legs) {
            assertNotClosed(leg.segment(), request.startTime(), request.endTime());
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
     * -> 统一顺序加锁新旧航段 -> 关闭区域与容量判定 -> 先取得全部新航段容量 -> 再释放不再使用的旧航段。
     * 任一校验或容量判定失败都抛异常整体回滚，原许可与原占用保持不变。
     * 改道后不允许残留该航班的待处理关闭标记：新航线仍穿越的关闭由该关闭的改道流程处理。
     */
    @Transactional
    public FlightClearance reroute(String externalNo, RerouteRequest request) {
        clearanceRepository.findByExternalNoForUpdate(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        assertRerouteable(clearance, request.expectedVersion(), externalNo);
        validateRerouteWindow(clearance, request.fromLegIndex(), request.effectiveAt());
        return applyReroute(clearance, request.rerouteNo(), null, null,
                request.fromLegIndex(), request.effectiveAt(), request.legs());
    }

    /**
     * 关闭改道：在关闭事件锁 + 许可锁下确认替代航线。
     * 关闭范围版本、许可航线版本、航班状态任一变化即拒绝旧方案；
     * 替代航线必须避开该关闭区域（未飞航段）且容量充足；
     * 新旧容量切换与该关闭全部待处理标记的处理状态更新在同一事务内完成。
     */
    @Transactional
    public FlightClearance confirmClosureReroute(String eventNo, String externalNo,
                                                 ClosureRerouteRequest request) {
        AirspaceClosure closure = closureRepository.findByEventNoForUpdate(eventNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + eventNo));
        if (closure.getStatus() != ClosureStatus.ACTIVE) {
            throw new StateConflictException("关闭事件已取消，无需改道：" + eventNo);
        }
        if (closure.getScopeVersion() != request.expectedScopeVersion()) {
            throw new StateConflictException("关闭范围已变化：请求基于版本 "
                    + request.expectedScopeVersion() + "，当前版本 " + closure.getScopeVersion());
        }

        clearanceRepository.findByExternalNoForUpdate(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        assertRerouteable(clearance, request.expectedClearanceVersion(), externalNo);

        List<ClosureImpact> pending = impactRepository.findPendingByClearanceForUpdate(clearance.getId());
        List<ClosureImpact> forClosure = pending.stream()
                .filter(i -> i.getClosure().getId().equals(closure.getId()))
                .toList();
        if (forClosure.isEmpty()) {
            throw new StateConflictException("航班 " + externalNo + " 没有关闭事件 " + eventNo
                    + " 的待处理受影响航段（可能已处理、已进入或限制已解除）");
        }
        if (clearance.getVersion() != request.expectedClearanceVersion()) {
            throw new StateConflictException("许可版本已变化：请求基于版本 "
                    + request.expectedClearanceVersion() + "，当前版本 " + clearance.getVersion());
        }

        int earliestAffected = forClosure.stream().mapToInt(ClosureImpact::getSegmentOrder).min().orElseThrow();
        int from = request.fromLegIndex() == null ? earliestAffected : request.fromLegIndex();
        if (from > earliestAffected) {
            throw new StateConflictException("改道起点 " + from + " 晚于最早受影响航段 "
                    + earliestAffected + "，无法避开关闭区域");
        }
        validateRerouteWindow(clearance, from, request.effectiveAt());

        // 关闭区域判定：替代航线的未飞航段不得再次进入该关闭在关闭时段覆盖的航段。
        // 关闭锁已持有，范围内航段集合在本事务内稳定。
        Set<Long> closedSegmentIds = closure.getSegments().stream()
                .map(cs -> cs.getSegment().getId())
                .collect(java.util.stream.Collectors.toSet());
        Instant closureOverlapStart = request.effectiveAt().isAfter(closure.getStartTime())
                ? request.effectiveAt() : closure.getStartTime();
        Instant closureOverlapEnd = clearance.getEndTime().isBefore(closure.getEndTime())
                ? clearance.getEndTime() : closure.getEndTime();
        boolean timeOverlap = closureOverlapStart.isBefore(closureOverlapEnd);
        for (SubmitClearanceRequest.LegRequest leg : request.legs()) {
            AirSegment segment = segmentRepository.findByCode(leg.segmentCode())
                    .orElseThrow(() -> new ParamInvalidException("航段不存在：" + leg.segmentCode()));
            if (timeOverlap && closedSegmentIds.contains(segment.getId())) {
                throw new ClosureConflictException("替代航线仍经过关闭航段 " + leg.segmentCode()
                        + "（关闭事件 " + eventNo + "）");
            }
        }

        FlightClearance updated = applyReroute(clearance, request.rerouteNo(), eventNo, closure,
                from, request.effectiveAt(), request.legs());

        // applyReroute 已把仍待处理的标记视为冲突拒绝；走到这里该关闭的标记必然全部处理完毕。
        boolean allHandled = impactRepository.findPendingByClearanceForUpdate(clearance.getId()).stream()
                .noneMatch(i -> i.getClosure().getId().equals(closure.getId()));
        if (!allHandled) {
            throw new ClosureConflictException("改道后航班仍有待处理的关闭航段：" + eventNo);
        }
        return updated;
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
     * 航班已进入（序号小于已飞数）的待处理关闭标记置为 EXPIRED，不能反向修改；
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

        expireEnteredImpacts(clearance, flownLegCount);
        return clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
    }

    // ------------------------------------------------------------------
    // 改道核心：新旧容量切换、航线版本、占用事件、关闭标记同事务处理
    // ------------------------------------------------------------------

    private void assertRerouteable(FlightClearance clearance, int expectedVersion, String externalNo) {
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
        if (clearance.getVersion() != expectedVersion) {
            throw new StateConflictException("许可版本已变化：请求基于版本 " + expectedVersion
                    + "，当前版本 " + clearance.getVersion());
        }
    }

    private void validateRerouteWindow(FlightClearance clearance, int from, Instant effectiveAt) {
        List<SegmentReservation> current = clearance.getReservations();
        if (from < 0 || from >= current.size()) {
            throw new ParamInvalidException("改道起点航段序号超出范围：" + from
                    + "（当前航线共 " + current.size() + " 段）");
        }
        if (from < clearance.getFlownLegCount()) {
            throw new StateConflictException("改道起点 " + from + " 早于当前飞行位置 "
                    + clearance.getFlownLegCount() + "，已飞过的航段不可改写");
        }
        Instant now = Instant.now(clock);
        if (effectiveAt.isBefore(now)) {
            throw new ParamInvalidException("生效时间不能早于当前时间");
        }
        if (!effectiveAt.isBefore(clearance.getEndTime())) {
            throw new ParamInvalidException("生效时间必须早于许可结束时间");
        }
    }

    /**
     * 执行换线。closure 非空时为关闭改道，rerouteNo 对应的航线版本记录关闭业务号。
     * 加锁顺序：许可 -> 航段（主键升序）-> 该许可的待处理关闭标记（主键升序），
     * 关闭改道在进入本方法前已先取关闭事件锁。
     */
    private FlightClearance applyReroute(FlightClearance clearance, String rerouteNo, String closureEventNo,
                                         AirspaceClosure lockedClosure, int from, Instant effectiveAt,
                                         List<SubmitClearanceRequest.LegRequest> requestedLegs) {
        List<SegmentReservation> current = clearance.getReservations();

        // 保留前缀：[0, from) 的航段原样保留，新航段不得与之重复
        List<SegmentReservation> keptPrefix = new ArrayList<>(current.subList(0, from));
        List<SegmentReservation> oldTail = new ArrayList<>(current.subList(from, current.size()));
        Set<String> keptCodes = new HashSet<>();
        for (SegmentReservation kept : keptPrefix) {
            keptCodes.add(kept.getSegment().getCode());
        }
        for (SubmitClearanceRequest.LegRequest leg : requestedLegs) {
            if (keptCodes.contains(leg.segmentCode())) {
                throw new ParamInvalidException("新航线与保留航段重复：" + leg.segmentCode());
            }
        }

        // 统一顺序一次性加锁新旧航段（与提交、取消互斥），再逐个判定高度
        Set<Long> oldTailSegmentIds = new HashSet<>();
        for (SegmentReservation reservation : oldTail) {
            oldTailSegmentIds.add(reservation.getSegment().getId());
        }
        List<LegInput> newLegs = resolveAndLockLegs(requestedLegs, oldTailSegmentIds);

        Instant windowEnd = clearance.getEndTime();
        // 容量与关闭区域判定基于 [生效时间, 许可结束时间)，排除自身已有占用
        for (LegInput leg : newLegs) {
            assertNotClosed(leg.segment(), effectiveAt, windowEnd);
            long overlapping = reservationRepository.countActiveOverlapsExcluding(
                    leg.segment().getId(), effectiveAt, windowEnd, clearance.getId());
            if (overlapping + 1 > leg.segment().getCapacity()) {
                throw new CapacityExceededException("航段 " + leg.segment().getCode()
                        + " 在改道生效时间区间内剩余容量不足：已占用 " + overlapping
                        + "，容量 " + leg.segment().getCapacity());
            }
        }

        // 取该许可全部待处理关闭标记并加锁（关闭改道路径关闭锁已持有，此处不会死锁）
        List<ClosureImpact> pendingImpacts =
                impactRepository.findPendingByClearanceForUpdate(clearance.getId());

        // 容量全部就绪后换线：删除旧尾部占用（先 flush 释放唯一约束），再写入新航段
        clearance.getReservations().removeAll(oldTail);
        reservationRepository.deleteAll(oldTail);
        reservationRepository.flush();
        for (LegInput leg : newLegs) {
            clearance.addReservation(from + leg.order(), leg.segment(), leg.altitude());
        }

        int newVersion = clearance.getVersion() + 1;
        Instant now = Instant.now(clock);

        // 新航线中尚未飞航段占用的航段集合，用于关闭标记对账
        Set<Long> unflownNewSegmentIds = new HashSet<>();
        for (SegmentReservation reservation : clearance.getReservations()) {
            if (reservation.getSegmentOrder() >= clearance.getFlownLegCount()) {
                unflownNewSegmentIds.add(reservation.getSegment().getId());
            }
        }

        reconcileImpactsAfterReroute(pendingImpacts, clearance, unflownNewSegmentIds,
                effectiveAt, rerouteNo, newVersion, lockedClosure);

        // 事件按先 ACQUIRE 后 RELEASE 记录净变化
        Set<Long> newSegmentIds = new HashSet<>();
        for (LegInput leg : newLegs) {
            newSegmentIds.add(leg.segment().getId());
        }
        for (LegInput leg : newLegs) {
            if (!oldTailSegmentIds.contains(leg.segment().getId())) {
                recordEvent(clearance, leg.segment(), OccupancyEventType.ACQUIRE, leg.altitude(),
                        newVersion, rerouteNo, now);
            }
        }
        for (SegmentReservation reservation : oldTail) {
            if (!newSegmentIds.contains(reservation.getSegment().getId())) {
                recordEvent(clearance, reservation.getSegment(), OccupancyEventType.RELEASE,
                        reservation.getAltitude(), newVersion, rerouteNo, now);
            }
        }

        // 航线历史快照：完整新航线 = 保留前缀 + 新航段
        RouteVersion routeVersion = new RouteVersion(clearance, newVersion, rerouteNo, closureEventNo,
                from, effectiveAt, now);
        for (SegmentReservation kept : keptPrefix) {
            routeVersion.addLeg(kept.getSegmentOrder(), kept.getSegment(), kept.getAltitude());
        }
        for (LegInput leg : newLegs) {
            routeVersion.addLeg(from + leg.order(), leg.segment(), leg.altitude());
        }
        routeVersionRepository.save(routeVersion);

        clearance.bumpVersion();
        clearanceRepository.saveAndFlush(clearance);
        return clearanceRepository.findDetailByExternalNo(clearance.getExternalNo()).orElseThrow();
    }

    /**
     * 换线后对该航班全部待处理关闭标记对账（同事务）：
     * <ul>
     *   <li>新航线未飞部分仍使用该关闭航段：关闭改道下不应出现（已前置校验），
     *       普通改道下抛出 {@link ClosureConflictException}，事务回滚、容量切换作废；</li>
     *   <li>新航线已避开：标记 HANDLED 并记录改道业务号与新版本；</li>
     *   <li>航段已被飞出：EXPIRED（正常由位置上报先行置位，此处为防御）。</li>
     * </ul>
     */
    private void reconcileImpactsAfterReroute(List<ClosureImpact> pendingImpacts,
                                              FlightClearance clearance,
                                              Set<Long> unflownNewSegmentIds,
                                              Instant effectiveAt, String rerouteNo, int newVersion,
                                              AirspaceClosure lockedClosure) {
        for (ClosureImpact impact : pendingImpacts) {
            AirspaceClosure closure = impact.getClosure();
            boolean isLockedTarget = lockedClosure != null
                    && closure.getId().equals(lockedClosure.getId());

            if (impact.getSegmentOrder() < clearance.getFlownLegCount()) {
                impact.markExpired();
                continue;
            }
            if (unflownNewSegmentIds.contains(impact.getSegmentId())) {
                if (isLockedTarget) {
                    // confirmClosureReroute 已逐段校验，走到这里属于内部不一致
                    throw new ClosureConflictException("替代航线仍经过关闭航段：关闭事件 "
                            + closure.getEventNo());
                }
                throw new ClosureConflictException("改道后航线仍经过关闭航段（关闭事件 "
                        + closure.getEventNo() + "），请使用关闭改道流程处理航班 "
                        + clearance.getExternalNo());
            }
            // 该关闭航段已从新航线未飞部分移除：限制已被避开
            impact.markHandled(rerouteNo, newVersion);
        }
    }

    /**
     * 位置上报后：航班已进入（航段序号小于已飞数）的待处理标记置为 EXPIRED。
     */
    private void expireEnteredImpacts(FlightClearance clearance, int flownLegCount) {
        List<ClosureImpact> pending = impactRepository.findPendingByClearanceForUpdate(clearance.getId());
        boolean changed = false;
        for (ClosureImpact impact : pending) {
            if (impact.getSegmentOrder() < flownLegCount) {
                impact.markExpired();
                changed = true;
            }
        }
        if (changed) {
            impactRepository.flush();
        }
    }

    private void assertNotClosed(AirSegment segment, Instant startTime, Instant endTime) {
        long closures = closureSegmentRepository.countActiveClosures(
                segment.getId(), startTime, endTime);
        if (closures > 0) {
            throw new ClosureConflictException("航段 " + segment.getCode()
                    + " 在 " + startTime + " 至 " + endTime + " 处于临时空域关闭中");
        }
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
