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
import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.OccupancyEventType;
import com.chris64233.cc.airspace.domain.RouteVersion;
import com.chris64233.cc.airspace.domain.SegmentOccupancyEvent;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
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
 *
 * 全局加锁层次（所有事务严格按此顺序，避免死锁）：
 * 关闭事件行(level0) -&gt; 航段行(level1) -&gt; 许可行(level2) -&gt;
 * 占用 / 关闭影响标记等子记录(level3)。
 * 要锁哪些 level0 行先以非锁读探测，加锁后再重新校验；漏探测的关闭事件
 * 由航段锁之后的复查与对方事务的锁后重扫兜底。
 */
@Service
public class ClearanceTxService {

    private final AirSegmentRepository segmentRepository;
    private final AirspaceClosureRepository closureRepository;
    private final ClosureImpactRepository impactRepository;
    private final FlightClearanceRepository clearanceRepository;
    private final SegmentReservationRepository reservationRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final SegmentOccupancyEventRepository occupancyEventRepository;
    private final Clock clock;

    public ClearanceTxService(AirSegmentRepository segmentRepository,
                              AirspaceClosureRepository closureRepository,
                              ClosureImpactRepository impactRepository,
                              FlightClearanceRepository clearanceRepository,
                              SegmentReservationRepository reservationRepository,
                              RouteVersionRepository routeVersionRepository,
                              SegmentOccupancyEventRepository occupancyEventRepository,
                              Clock clock) {
        this.segmentRepository = segmentRepository;
        this.closureRepository = closureRepository;
        this.impactRepository = impactRepository;
        this.clearanceRepository = clearanceRepository;
        this.reservationRepository = reservationRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.occupancyEventRepository = occupancyEventRepository;
        this.clock = clock;
    }

    /**
     * 原子提交：统一顺序加锁全部航段 -&gt; 复查有效关闭事件（穿越关闭区域直接拒绝）
     * -&gt; 逐段判定容量 -&gt; 一次性写入许可与全部占用，
     * 并记录初始航线版本（v1）与逐航段 ACQUIRE 事件。
     * 容量不足抛 {@link CapacityExceededException}，事务整体回滚，不会留下部分占用。
     * 外部申请号唯一约束冲突抛出 DataIntegrityViolationException，由门面层处理幂等。
     */
    @Transactional
    public FlightClearance submit(SubmitClearanceRequest request) {
        List<LegInput> legs = resolveAndLockLegs(request.legs());

        rejectIfClosedSegments(legs.stream().map(l -> l.segment().getId()).toList(),
                request.startTime(), request.endTime(), null);

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
     * 原子取消：按关闭事件 -&gt; 航段 -&gt; 许可的顺序加锁（与改道、关闭取消互斥）
     * -&gt; 校验是否已到开始时间 -&gt; 条件更新保证 ACTIVE -&gt; CANCELLED 只生效一次，
     * 记录全部航段的 RELEASE 事件，并丢弃尚未处理的关闭影响标记
     * （已 REROUTED 的运行记录保留）。
     */
    @Transactional
    public CancelOutcome cancel(String externalNo) {
        Long clearanceId = clearanceRepository.findIdByExternalNo(externalNo)
                .orElse(null);
        if (clearanceId == null) {
            return CancelOutcome.notFound();
        }

        FlightClearanceRepository.ClearanceWindow window =
                clearanceRepository.findWindowByExternalNo(externalNo).orElseThrow();
        List<Long> segmentIds = reservationRepository.findSegmentIdsByClearanceId(clearanceId).stream()
                .sorted().toList();
        lockClosuresForSegments(segmentIds, window.getStartTime(), window.getEndTime());
        segmentRepository.findAllByIdForUpdateOrderById(segmentIds);
        clearanceRepository.findByExternalNoForUpdate(externalNo).orElseThrow();
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();

        if (clearance.getStatus() == ClearanceStatus.CANCELLED) {
            return CancelOutcome.done(clearance);
        }
        if (clearance.getStatus() == ClearanceStatus.COMPLETED) {
            throw new StateConflictException("已完成的许可不能取消：" + externalNo);
        }

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
        impactRepository.deletePendingByClearance(clearance.getId(), ClosureImpactStatus.PENDING);
        FlightClearance refreshed = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        return CancelOutcome.done(refreshed);
    }

    /**
     * 原子改道：关闭事件 -&gt; 新旧航段 -&gt; 许可依次加锁后，校验状态、版本、衔接位置、
     * 关闭方案有效性，再先取得全部新航段容量、在同一事务中释放不再使用的旧航段。
     * 任一校验或容量判定失败都抛异常整体回滚，原许可与原占用保持不变。
     * 换线成功后，受影响航段标记在同事务内置为 REROUTED（含顺带绕开的其他有效关闭）。
     */
    @Transactional
    public FlightClearance reroute(String externalNo, RerouteRequest request) {
        // 加锁前只用标量探测加锁集合，不把许可 / 关闭实体装入一级缓存，避免持锁后读到旧值
        Long clearanceId = clearanceRepository.findIdByExternalNo(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        FlightClearanceRepository.ClearanceWindow window =
                clearanceRepository.findWindowByExternalNo(externalNo).orElseThrow();

        Set<Long> newLegSegmentIds = new HashSet<>();
        for (SubmitClearanceRequest.LegRequest leg : request.legs()) {
            newLegSegmentIds.add(segmentRepository.findIdByCode(leg.segmentCode())
                    .orElseThrow(() -> new ParamInvalidException("航段不存在：" + leg.segmentCode())));
        }

        Long referencedClosureId = null;
        if (request.referencesClosure()) {
            referencedClosureId = closureRepository.findIdByClosureNo(request.closureNo())
                    .orElseThrow(() -> new StateConflictException(
                            "关闭事件不存在：" + request.closureNo()));
        }

        Set<Long> discoverySegmentIds =
                new HashSet<>(reservationRepository.findSegmentIdsByClearanceId(clearanceId));
        discoverySegmentIds.addAll(newLegSegmentIds);

        // level0：探测并锁定所有相关有效关闭事件（引用的关闭事件即使不在最终航线上也显式锁定）
        Set<Long> lockClosureIds = new HashSet<>(lockClosuresForSegments(
                discoverySegmentIds.stream().sorted().toList(),
                window.getStartTime(), window.getEndTime()).stream()
                .map(AirspaceClosure::getId)
                .toList());
        if (referencedClosureId != null) {
            lockClosureIds.add(referencedClosureId);
        }
        Map<Long, AirspaceClosure> lockedClosures = new LinkedHashMap<>();
        if (!lockClosureIds.isEmpty()) {
            for (AirspaceClosure c : closureRepository.findAllByIdForUpdateOrderById(
                    lockClosureIds.stream().sorted().toList())) {
                lockedClosures.put(c.getId(), c);
            }
        }

        // level1：统一顺序一次性加锁新旧航段与保留前缀航段
        List<Long> currentSegmentIds =
                reservationRepository.findSegmentIdsByClearanceId(clearanceId);
        List<LegInput> newLegs = resolveAndLockLegs(request.legs(), new HashSet<>(currentSegmentIds));

        // level2：许可行锁串行化并发变更
        clearanceRepository.findByExternalNoForUpdate(externalNo).orElseThrow();
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

        // 关闭方案校验：引用的关闭事件必须仍有效、范围版本未变且航班确有尚未处理的受影响航段
        List<ClosureImpact> referencedPendingImpacts = List.of();
        if (referencedClosureId != null) {
            AirspaceClosure lockedReferenced = lockedClosures.get(referencedClosureId);
            if (lockedReferenced == null) {
                throw new StateConflictException("关闭方案基于的关闭事件状态已变化："
                        + request.closureNo());
            }
            validateReferencedClosure(lockedReferenced, request, clearance);
            referencedPendingImpacts = impactRepository
                    .findByClosureAndClearance(lockedReferenced.getId(), clearance.getId()).stream()
                    .filter(i -> i.getStatus() == ClosureImpactStatus.PENDING)
                    .toList();
            if (referencedPendingImpacts.isEmpty()) {
                throw new StateConflictException("航班 " + externalNo + " 已不受关闭事件 "
                        + request.closureNo() + " 的待处理限制，方案已过期");
            }
        }

        // 最终航线不得穿越任何有效关闭区域：保留前缀中仅检查尚未飞入的航段（已飞航段
        // 既成事实不可反向修改），新航段按生效区间判定
        List<Long> unflewKeptIds = keptPrefix.stream()
                .filter(r -> r.getSegmentOrder() >= clearance.getFlownLegCount())
                .map(r -> r.getSegment().getId())
                .toList();
        rejectIfClosedSegments(unflewKeptIds, clearance.getStartTime(), clearance.getEndTime(),
                request.referencesClosure() ? request.closureNo() : null);
        rejectIfClosedSegments(newLegs.stream().map(l -> l.segment().getId()).toList(),
                request.effectiveAt(), clearance.getEndTime(),
                request.referencesClosure() ? request.closureNo() : null);

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

        Set<Long> finalSegmentIds = new HashSet<>();
        for (SegmentReservation kept : keptPrefix) {
            finalSegmentIds.add(kept.getSegment().getId());
        }
        for (LegInput leg : newLegs) {
            finalSegmentIds.add(leg.segment().getId());
        }

        // 再释放不再使用的旧航段：事件按先 ACQUIRE 后 RELEASE 记录净变化
        int newVersion = clearance.getVersion() + 1;
        Set<Long> oldTailSegmentIds = new HashSet<>();
        for (SegmentReservation reservation : oldTail) {
            oldTailSegmentIds.add(reservation.getSegment().getId());
        }
        for (LegInput leg : newLegs) {
            if (!oldTailSegmentIds.contains(leg.segment().getId())) {
                recordEvent(clearance, leg.segment(), OccupancyEventType.ACQUIRE, leg.altitude(),
                        newVersion, request.rerouteNo(), now);
            }
        }
        for (SegmentReservation reservation : oldTail) {
            if (!finalSegmentIds.contains(reservation.getSegment().getId())) {
                recordEvent(clearance, reservation.getSegment(), OccupancyEventType.RELEASE,
                        reservation.getAltitude(), newVersion, request.rerouteNo(), now);
            }
        }

        // 航线历史快照：完整新航线 = 保留前缀 + 新航段；关闭驱动的改道记录关闭事件与范围版本
        RouteVersion routeVersion = new RouteVersion(clearance, newVersion, request.rerouteNo(),
                request.referencesClosure() ? request.closureNo() : null,
                request.referencesClosure() ? request.expectedClosureVersion() : null,
                from, request.effectiveAt(), now);
        for (SegmentReservation kept : keptPrefix) {
            routeVersion.addLeg(kept.getSegmentOrder(), kept.getSegment(), kept.getAltitude());
        }
        for (LegInput leg : newLegs) {
            routeVersion.addLeg(from + leg.order(), leg.segment(), leg.altitude());
        }
        routeVersionRepository.save(routeVersion);

        // 同事务处理关闭影响：最终航线已离开的受影响航段标记为 REROUTED（已加锁的关闭事件范围内），
        // 引用关闭事件的待处理标记必然全部在此集合中
        for (AirspaceClosure lockedClosure : lockedClosures.values()) {
            List<ClosureImpact> pending = impactRepository
                    .findByClosureAndClearance(lockedClosure.getId(), clearance.getId()).stream()
                    .filter(i -> i.getStatus() == ClosureImpactStatus.PENDING)
                    .toList();
            for (ClosureImpact impact : pending) {
                if (!finalSegmentIds.contains(impact.getSegment().getId())) {
                    impact.markRerouted(request.rerouteNo(), now);
                }
            }
        }

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
     * 全部航段飞完后许可置为 COMPLETED，并丢弃尚未处理的关闭影响标记
     * （已完成改道记录保留）。先锁相关关闭事件行再锁许可，遵循统一加锁层次。
     */
    @Transactional
    public FlightClearance reportProgress(String externalNo, int flownLegCount) {
        List<Long> impactClosureIds = impactRepository.findClosureIdsByClearanceExternalNo(externalNo);
        if (!impactClosureIds.isEmpty()) {
            closureRepository.findAllByIdForUpdateOrderById(impactClosureIds);
        }
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
        boolean completing = flownLegCount == clearance.getReservations().size();
        if (completing) {
            clearance.markCompleted();
        }
        clearanceRepository.saveAndFlush(clearance);
        // 已飞入关闭区域的航段成为既成运行事实：解除其尚未处理标记；
        // 飞完全部航段（或取消）时其余 PENDING 标记一并丢弃，已 REROUTED 记录始终保留
        impactRepository.deletePendingFlownByClearance(clearance.getId(),
                ClosureImpactStatus.PENDING, flownLegCount);
        if (completing) {
            impactRepository.deletePendingByClearance(clearance.getId(), ClosureImpactStatus.PENDING);
        }
        return clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
    }

    /**
     * 校验关闭驱动的改道方案：关闭事件必须仍有效，且申请基于的范围版本必须等于当前版本。
     * 关闭取消、范围 / 时段修订后旧方案一律拒绝。
     */
    private void validateReferencedClosure(AirspaceClosure closure, RerouteRequest request,
                                           FlightClearance clearance) {
        if (closure.getStatus() == com.chris64233.cc.airspace.domain.ClosureStatus.CANCELLED) {
            throw new StateConflictException("关闭事件 " + request.closureNo()
                    + " 已取消，该改道方案失效：" + clearance.getExternalNo());
        }
        if (request.expectedClosureVersion() == null
                || closure.getScopeVersion() != request.expectedClosureVersion()) {
            throw new StateConflictException("关闭事件 " + request.closureNo() + " 的范围已变化：方案基于版本 "
                    + request.expectedClosureVersion() + "，当前版本 " + closure.getScopeVersion());
        }
    }

    /**
     * 非锁复查：若给定航段在时间窗内被有效关闭事件覆盖则拒绝（提交 / 改道最终航线校验）。
     * 调用方已持有相关航段写锁与相关关闭事件写锁；修订中的关闭事件无法在本事务持有
     * 航段锁期间提交，漏网情形由对方事务的锁后重扫补标。
     *
     * @param excludeClosureNo 关闭驱动改道时，引用的关闭事件不在此排除——
     *                         最终航线若仍经过其区域同样拒绝（这里仅用于错误信息区分）
     */
    private void rejectIfClosedSegments(List<Long> segmentIds, Instant startTime, Instant endTime,
                                        String referencedClosureNo) {
        if (segmentIds.isEmpty()) {
            return;
        }
        List<AirspaceClosure> closures = closureRepository.findActiveForSegmentsAndWindow(
                segmentIds, startTime, endTime);
        if (!closures.isEmpty()) {
            AirspaceClosure closure = closures.get(0);
            throw new StateConflictException("航段在关闭事件 " + closure.getClosureNo()
                    + " 的时段 " + closure.getStartTime() + " 至 " + closure.getEndTime()
                    + " 内临时关闭，无法放行或改道经过"
                    + (closure.getClosureNo().equals(referencedClosureNo)
                            ? "（替代航线仍穿越关闭区域）" : ""));
        }
    }

    /**
     * 探测时间窗内覆盖给定航段的有效关闭事件，并按主键升序对其加写锁（level0）。
     * 预探测可能漏看并发修订 / 新建的关闭事件；调用方持有航段锁后会重新非锁复查，
     * 对方事务也会在拿到航段锁后重扫，两侧校验保证不漏。
     */
    private List<AirspaceClosure> lockClosuresForSegments(List<Long> segmentIds, Instant startTime,
                                                          Instant endTime) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        return closureRepository.findActiveForSegmentsAndWindowForUpdate(segmentIds, startTime, endTime);
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
