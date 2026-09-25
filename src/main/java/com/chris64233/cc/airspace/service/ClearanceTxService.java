package com.chris64233.cc.airspace.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chris64233.cc.airspace.domain.AirSegment;
import com.chris64233.cc.airspace.domain.ClearanceStatus;
import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.error.CapacityExceededException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;

/**
 * 承载真正的数据库事务：航段行锁、容量判定、条件取消均在数据库事务内完成，
 * 不依赖 JVM 内存状态，保证进程重启与并发下的正确性。
 */
@Service
public class ClearanceTxService {

    private final AirSegmentRepository segmentRepository;
    private final FlightClearanceRepository clearanceRepository;
    private final SegmentReservationRepository reservationRepository;
    private final Clock clock;

    public ClearanceTxService(AirSegmentRepository segmentRepository,
                              FlightClearanceRepository clearanceRepository,
                              SegmentReservationRepository reservationRepository,
                              Clock clock) {
        this.segmentRepository = segmentRepository;
        this.clearanceRepository = clearanceRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    /**
     * 原子提交：统一顺序加锁全部航段 -> 逐段判定容量 -> 一次性写入许可与全部占用。
     * 容量不足抛 {@link CapacityExceededException}，事务整体回滚，不会留下部分占用。
     * 外部申请号唯一约束冲突抛出 DataIntegrityViolationException，由门面层处理幂等。
     */
    @Transactional
    public FlightClearance submit(SubmitClearanceRequest request) {
        List<LegInput> legs = resolveAndLockLegs(request);

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
        return clearanceRepository.saveAndFlush(clearance);
    }

    /**
     * 原子取消：加载许可与占用 -> 按统一顺序锁定涉及航段（与提交互斥）
     * -> 校验是否已到开始时间 -> 条件更新保证 ACTIVE -> CANCELLED 只生效一次。
     */
    @Transactional
    public CancelOutcome cancel(String externalNo) {
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo).orElse(null);
        if (clearance == null) {
            return CancelOutcome.notFound();
        }
        if (clearance.getStatus() == ClearanceStatus.CANCELLED) {
            return CancelOutcome.done(clearance);
        }

        List<Long> segmentIds = clearance.getReservations().stream()
                .map(r -> r.getSegment().getId())
                .sorted()
                .toList();
        segmentRepository.findAllByIdForUpdateOrderById(segmentIds);

        if (!clearance.getStartTime().isAfter(Instant.now(clock))) {
            throw new StateConflictException("已到达飞行开始时间，许可不得取消：" + externalNo);
        }

        clearanceRepository.cancelIfActive(externalNo,
                ClearanceStatus.ACTIVE, ClearanceStatus.CANCELLED, Instant.now(clock));
        FlightClearance refreshed = clearanceRepository.findDetailByExternalNo(externalNo).orElseThrow();
        return CancelOutcome.done(refreshed);
    }

    private List<LegInput> resolveAndLockLegs(SubmitClearanceRequest request) {
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        List<Long> segmentIds = new ArrayList<>();
        List<SubmitClearanceRequest.LegRequest> legs = request.legs();
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

        List<Long> sortedIds = segmentIds.stream().sorted().toList();
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
