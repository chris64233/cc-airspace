package com.chris64233.cc.airspace.service;

import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.chris64233.cc.airspace.domain.FlightClearance;
import com.chris64233.cc.airspace.domain.RouteVersion;
import com.chris64233.cc.airspace.domain.RouteVersionLeg;
import com.chris64233.cc.airspace.domain.SegmentOccupancyEvent;
import com.chris64233.cc.airspace.domain.SegmentReservation;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.repo.SegmentOccupancyEventRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.web.dto.ClearanceView;
import com.chris64233.cc.airspace.web.dto.LegConflictView;
import com.chris64233.cc.airspace.web.dto.LegOccupancyView;
import com.chris64233.cc.airspace.web.dto.OccupancyEventView;
import com.chris64233.cc.airspace.web.dto.RerouteRequest;
import com.chris64233.cc.airspace.web.dto.RouteLegView;
import com.chris64233.cc.airspace.web.dto.RouteVersionView;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;

/**
 * 业务门面：参数校验、外部申请号 / 改道业务号幂等处理、视图组装。
 * 真正的容量判定、取消与改道由 {@link ClearanceTxService} 在数据库事务中完成。
 */
@Service
public class ClearanceService {

    private final ClearanceTxService txService;
    private final FlightClearanceRepository clearanceRepository;
    private final SegmentReservationRepository reservationRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final SegmentOccupancyEventRepository occupancyEventRepository;
    private final Clock clock;

    public ClearanceService(ClearanceTxService txService,
                            FlightClearanceRepository clearanceRepository,
                            SegmentReservationRepository reservationRepository,
                            RouteVersionRepository routeVersionRepository,
                            SegmentOccupancyEventRepository occupancyEventRepository,
                            Clock clock) {
        this.txService = txService;
        this.clearanceRepository = clearanceRepository;
        this.reservationRepository = reservationRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.occupancyEventRepository = occupancyEventRepository;
        this.clock = clock;
    }

    public record SubmitOutcome(ClearanceView view, boolean created) {
    }

    public record RerouteOutcome(ClearanceView view, boolean created) {
    }

    /**
     * 提交飞行申请。
     * 相同外部申请号 + 相同内容：返回原许可（幂等）。
     * 相同外部申请号 + 不同内容：幂等冲突。
     */
    public SubmitOutcome submit(SubmitClearanceRequest request) {
        validate(request);

        FlightClearance existing = clearanceRepository.findDetailByExternalNo(request.externalNo()).orElse(null);
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
            FlightClearance raced = clearanceRepository.findDetailByExternalNo(request.externalNo())
                    .orElseThrow(() -> e);
            if (!sameContent(raced, request)) {
                throw new IdempotentConflictException(
                        "外部申请号 " + request.externalNo() + " 已存在但申请内容不同");
            }
            return new SubmitOutcome(toView(raced), false);
        }
    }

    /**
     * 申请改道：引用许可当前版本与衔接位置，给出替换尾部的新有序航段与生效时间。
     * 相同改道业务号 + 相同内容：返回上次结果（幂等）；内容不同：幂等冲突。
     * 版本或位置过期、容量不足、许可已取消 / 已完成：拒绝且原许可与原占用保持不变。
     */
    public RerouteOutcome reroute(String externalNo, RerouteRequest request) {
        validate(request);

        RouteVersion existing = routeVersionRepository.findDetailByRerouteNo(request.rerouteNo()).orElse(null);
        if (existing != null) {
            if (!sameRerouteContent(existing, externalNo, request)) {
                throw new IdempotentConflictException(
                        "改道业务号 " + request.rerouteNo() + " 已存在但申请内容不同");
            }
            return new RerouteOutcome(get(externalNo), false);
        }

        try {
            FlightClearance updated = txService.reroute(externalNo, request);
            return new RerouteOutcome(toView(updated), true);
        } catch (DataIntegrityViolationException e) {
            RouteVersion raced = routeVersionRepository.findDetailByRerouteNo(request.rerouteNo())
                    .orElseThrow(() -> e);
            if (!sameRerouteContent(raced, externalNo, request)) {
                throw new IdempotentConflictException(
                        "改道业务号 " + request.rerouteNo() + " 已存在但申请内容不同");
            }
            return new RerouteOutcome(get(externalNo), false);
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

    /**
     * 飞行开始：标记起飞时间，重复开始幂等。
     */
    public ClearanceView start(String externalNo) {
        return toView(txService.start(externalNo));
    }

    /**
     * 位置上报：推进已飞过航段数；基于旧位置的上报被忽略，不会回退。
     */
    public ClearanceView reportProgress(String externalNo, int flownLegCount) {
        return toView(txService.reportProgress(externalNo, flownLegCount));
    }

    public ClearanceView get(String externalNo) {
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        return toView(clearance);
    }

    /**
     * 航线历史版本（含初始提交与每次改道的完整航线快照），按版本号升序。
     */
    public List<RouteVersionView> routeHistory(String externalNo) {
        clearanceRepository.findByExternalNo(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        return routeVersionRepository.findHistoryByExternalNo(externalNo).stream()
                .map(this::toVersionView)
                .toList();
    }

    /**
     * 逐航段占用事件（ACQUIRE / RELEASE），按发生顺序升序。
     */
    public List<OccupancyEventView> occupancyEvents(String externalNo) {
        clearanceRepository.findByExternalNo(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        return occupancyEventRepository.findByClearanceExternalNoOrderById(externalNo).stream()
                .map(event -> new OccupancyEventView(
                        event.getSegment().getCode(),
                        event.getEventType().name(),
                        event.getAltitude(),
                        event.getRouteVersion(),
                        event.getRerouteNo(),
                        event.getCreatedAt()))
                .toList();
    }

    /**
     * 冲突航段查询：当前有效航线中，时间窗内其他有效许可占用数已达到容量的航段。
     */
    public List<LegConflictView> conflicts(String externalNo) {
        FlightClearance clearance = clearanceRepository.findDetailByExternalNo(externalNo)
                .orElseThrow(() -> new ResourceNotFoundException("许可不存在：" + externalNo));
        List<LegConflictView> conflicts = new java.util.ArrayList<>();
        for (SegmentReservation reservation : clearance.getReservations()) {
            long overlapping = reservationRepository.countActiveOverlapsExcluding(
                    reservation.getSegment().getId(),
                    clearance.getStartTime(), clearance.getEndTime(), clearance.getId());
            if (overlapping >= reservation.getSegment().getCapacity()) {
                conflicts.add(new LegConflictView(
                        reservation.getSegmentOrder(),
                        reservation.getSegment().getCode(),
                        reservation.getSegment().getCapacity(),
                        overlapping));
            }
        }
        return conflicts;
    }

    private void validate(SubmitClearanceRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ParamInvalidException("结束时间必须晚于开始时间");
        }
    }

    private void validate(RerouteRequest request) {
        if (request.rerouteNo() == null || request.rerouteNo().isBlank()) {
            throw new ParamInvalidException("改道业务号不能为空");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 1) {
            throw new ParamInvalidException("许可版本必须为正数");
        }
        if (request.fromLegIndex() == null || request.fromLegIndex() < 0) {
            throw new ParamInvalidException("改道起点航段序号不能为负");
        }
        if (request.effectiveAt() == null) {
            throw new ParamInvalidException("生效时间不能为空");
        }
        if (request.legs() == null || request.legs().isEmpty()) {
            throw new ParamInvalidException("改道航段列表不能为空");
        }
        if (request.referencesClosure()
                && (request.expectedClosureVersion() == null || request.expectedClosureVersion() < 1)) {
            throw new ParamInvalidException("引用关闭事件的改道必须携带有效的关闭范围版本");
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

    /**
     * 幂等回放比对：存储的改道版本必须与本次请求引用的许可、基线版本、
     * 衔接位置、生效时间及新航段逐一相同。
     */
    private boolean sameRerouteContent(RouteVersion routeVersion, String externalNo, RerouteRequest request) {
        if (!routeVersion.getClearance().getExternalNo().equals(externalNo)
                || routeVersion.getVersion() - 1 != request.expectedVersion()
                || routeVersion.getFromLegIndex() != request.fromLegIndex()
                || !routeVersion.getEffectiveAt().equals(request.effectiveAt())) {
            return false;
        }
        // 关闭事件引用必须一致：普通改道的 closureNo 为 null
        boolean storedReferencesClosure = routeVersion.getClosureNo() != null;
        if (storedReferencesClosure != request.referencesClosure()) {
            return false;
        }
        if (storedReferencesClosure
                && (!routeVersion.getClosureNo().equals(request.closureNo())
                || !routeVersion.getClosureScopeVersion().equals(request.expectedClosureVersion()))) {
            return false;
        }
        List<RouteVersionLeg> storedLegs = routeVersion.getLegs();
        int from = routeVersion.getFromLegIndex();
        List<SubmitClearanceRequest.LegRequest> requestedLegs = request.legs();
        if (storedLegs.size() - from != requestedLegs.size()) {
            return false;
        }
        for (int i = 0; i < requestedLegs.size(); i++) {
            RouteVersionLeg stored = storedLegs.get(from + i);
            SubmitClearanceRequest.LegRequest requested = requestedLegs.get(i);
            if (stored.getSegmentOrder() != from + i
                    || !stored.getSegment().getCode().equals(requested.segmentCode())
                    || stored.getAltitude() != requested.altitude()) {
                return false;
            }
        }
        return true;
    }

    private RouteVersionView toVersionView(RouteVersion routeVersion) {
        List<RouteLegView> legs = routeVersion.getLegs().stream()
                .map(leg -> new RouteLegView(leg.getSegmentOrder(), leg.getSegment().getCode(), leg.getAltitude()))
                .toList();
        return new RouteVersionView(routeVersion.getVersion(), routeVersion.getRerouteNo(),
                routeVersion.getClosureNo(), routeVersion.getClosureScopeVersion(),
                routeVersion.getFromLegIndex(), routeVersion.getEffectiveAt(),
                routeVersion.getCreatedAt(), legs);
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
                clearance.getVersion(),
                clearance.getFlownLegCount(),
                clearance.getCreatedAt(),
                clearance.getStartedAt(),
                clearance.getCancelledAt(),
                legs);
    }
}
