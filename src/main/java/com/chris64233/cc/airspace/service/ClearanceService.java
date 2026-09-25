package com.chris64233.cc.airspace.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.web.dto.ClearanceView;
import com.chris64233.cc.airspace.web.dto.LegOccupancyView;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;

/**
 * 业务门面：参数校验、外部申请号幂等处理、视图组装。
 * 真正的容量判定与取消由 {@link ClearanceTxService} 在数据库事务中完成。
 */
@Service
public class ClearanceService {

    private final ClearanceTxService txService;
    private final FlightClearanceRepository clearanceRepository;
    private final SegmentReservationRepository reservationRepository;
    private final Clock clock;

    public ClearanceService(ClearanceTxService txService,
                            FlightClearanceRepository clearanceRepository,
                            SegmentReservationRepository reservationRepository,
                            Clock clock) {
        this.txService = txService;
        this.clearanceRepository = clearanceRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    public record SubmitOutcome(ClearanceView view, boolean created) {
    }

    /**
     * 提交飞行申请。
     * 相同外部申请号 + 相同内容：返回原许可（幂等）。
     * 相同外部申请号 + 不同内容：幂等冲突。
     */
    public SubmitOutcome submit(SubmitClearanceRequest request) {
        validate(request);

        FlightClearance existing = clearanceRepository.findByExternalNo(request.externalNo()).orElse(null);
        if (existing != null) {
            if (!sameContent(existing, request)) {
                throw new IdempotentConflictException(
                        "外部申请号 " + request.externalNo() + " 已存在但申请内容不同");
            }
            return new SubmitOutcome(toView(existing), false);
        }

        try {
            FlightClearance created = txService.submit(request);
            return new SubmitOutcome(toView(created), true);
        } catch (DataIntegrityViolationException e) {
            FlightClearance raced = clearanceRepository.findByExternalNo(request.externalNo())
                    .orElseThrow(() -> e);
            if (!sameContent(raced, request)) {
                throw new IdempotentConflictException(
                        "外部申请号 " + request.externalNo() + " 已存在但申请内容不同");
            }
            return new SubmitOutcome(toView(raced), false);
        }
    }

    /**
     * 取消许可：一次性释放全部航段容量。重复取消返回原结果；
     * 已到达飞行开始时间的许可不得取消。
     */
    public ClearanceView cancel(String externalNo) {
        ClearanceTxService.CancelOutcome outcome = txService.cancel(externalNo);
        if (!outcome.found()) {
            throw new ResourceNotFoundException("许可不存在：" + externalNo);
        }
        return toView(outcome.clearance());
    }

    public ClearanceView get(String externalNo) {
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        return toView(clearance);
    }

    private void validate(SubmitClearanceRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ParamInvalidException("结束时间必须晚于开始时间");
        }
    }

    private boolean sameContent(FlightClearance clearance, SubmitClearanceRequest request) {
        if (!clearance.getAircraft().equals(request.aircraft())
                || !clearance.getStartTime().equals(request.startTime())
                || !clearance.getEndTime().equals(request.endTime())) {
            return false;
        }
        List<SegmentReservation> reservations = clearance.getReservations();
        List<SubmitClearanceRequest.LegRequest> requestedLegs = request.legs();
        if (reservations.size() != requestedLegs.size()) {
            return false;
        }
        for (int i = 0; i < reservations.size(); i++) {
            SegmentReservation reservation = reservations.get(i);
            SubmitClearanceRequest.LegRequest leg = requestedLegs.get(i);
            if (reservation.getSegmentOrder() != i
                    || !reservation.getSegment().getCode().equals(leg.segmentCode())
                    || reservation.getAltitude() != leg.altitude()) {
                return false;
            }
        }
        return true;
    }

    private ClearanceView toView(FlightClearance clearance) {
        List<LegOccupancyView> legs = clearance.getReservations().stream()
                .map(reservation -> {
                    long overlapping = reservationRepository.countActiveOverlaps(
                            reservation.getSegment().getId(),
                            clearance.getStartTime(), clearance.getEndTime());
                    return new LegOccupancyView(
                            reservation.getSegmentOrder(),
                            reservation.getSegment().getCode(),
                            reservation.getAltitude(),
                            reservation.getSegment().getCapacity(),
                            overlapping);
                })
                .toList();
        return new ClearanceView(
                clearance.getExternalNo(),
                clearance.getAircraft(),
                clearance.getStartTime(),
                clearance.getEndTime(),
                clearance.getStatus().name(),
                clearance.getCreatedAt(),
                clearance.getCancelledAt(),
                legs);
    }
}
