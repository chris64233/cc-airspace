package com.chris64233.cc.airspace.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.chris64233.cc.airspace.domain.AirspaceClosure;
import com.chris64233.cc.airspace.domain.AirspaceClosureSegment;
import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.domain.ClosureImpactStatus;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.web.dto.ClosureImpactView;
import com.chris64233.cc.airspace.web.dto.ClosureRequest;
import com.chris64233.cc.airspace.web.dto.ClosureRerouteRequest;
import com.chris64233.cc.airspace.web.dto.ClosureView;
import com.chris64233.cc.airspace.web.dto.ClearanceView;

/**
 * 临时空域关闭门面：参数校验、eventNo 幂等、关闭改道幂等、视图组装。
 */
@Service
public class ClosureService {

    private final ClosureTxService closureTxService;
    private final ClearanceTxService clearanceTxService;
    private final ClearanceService clearanceService;
    private final AirspaceClosureRepository closureRepository;
    private final ClosureImpactRepository impactRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final AirSegmentRepository segmentRepository;

    public ClosureService(ClosureTxService closureTxService,
                          ClearanceTxService clearanceTxService,
                          ClearanceService clearanceService,
                          AirspaceClosureRepository closureRepository,
                          ClosureImpactRepository impactRepository,
                          RouteVersionRepository routeVersionRepository,
                          AirSegmentRepository segmentRepository) {
        this.closureTxService = closureTxService;
        this.clearanceTxService = clearanceTxService;
        this.clearanceService = clearanceService;
        this.closureRepository = closureRepository;
        this.impactRepository = impactRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.segmentRepository = segmentRepository;
    }

    public record ClosureOutcome(ClosureView view, boolean created) {
    }

    public record ClosureRerouteOutcome(ClearanceView view, boolean created) {
    }

    /**
     * 创建关闭事件并识别受影响航班。相同 eventNo + 相同内容：返回原事件（幂等）；
     * 相同 eventNo + 不同内容：幂等冲突（调整范围请使用 reschedule）。
     */
    public ClosureOutcome create(ClosureRequest request) {
        validate(request);

        AirspaceClosure existing = closureRepository.findDetailByEventNo(request.eventNo()).orElse(null);
        if (existing != null) {
            if (!sameContent(existing, request)) {
                throw new IdempotentConflictException(
                        "关闭事件号 " + request.eventNo() + " 已存在但内容不同");
            }
            return new ClosureOutcome(toView(existing), false);
        }

        try {
            AirspaceClosure created = closureTxService.create(request);
            return new ClosureOutcome(toView(created), true);
        } catch (DataIntegrityViolationException e) {
            AirspaceClosure raced = closureRepository.findDetailByEventNo(request.eventNo())
                    .orElseThrow(() -> e);
            if (!sameContent(raced, request)) {
                throw new IdempotentConflictException(
                        "关闭事件号 " + request.eventNo() + " 已存在但内容不同");
            }
            return new ClosureOutcome(toView(raced), false);
        }
    }

    /**
     * 调整关闭范围 / 时段：范围版本 +1，已下发但未确认的旧关闭改道方案会因版本过期被拒绝。
     */
    public ClosureView reschedule(ClosureRequest request) {
        validate(request);
        return toView(closureTxService.reschedule(request));
    }

    /**
     * 取消关闭：只解除尚未处理航班的限制，已完成的改道保留不撤销。重复取消幂等。
     */
    public ClosureView cancel(String eventNo) {
        AirspaceClosure closure = closureTxService.cancel(eventNo);
        if (closure == null) {
            throw new ResourceNotFoundException("关闭事件不存在：" + eventNo);
        }
        return toView(closure);
    }

    public ClosureView get(String eventNo) {
        return toView(closureRepository.findDetailByEventNo(eventNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + eventNo)));
    }

    public List<ClosureView> list() {
        return closureRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(AirspaceClosure::getId))
                .map(c -> closureRepository.findDetailByEventNo(c.getEventNo()).orElseThrow())
                .map(this::toView)
                .toList();
    }

    /**
     * 查询关闭事件的受影响航班与处理结果（按航班、航段顺序）。
     */
    public List<ClosureImpactView> impacts(String eventNo) {
        closureRepository.findByEventNo(eventNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + eventNo));
        return impactRepository.findDetailByEventNo(eventNo).stream()
                .map(this::toImpactView)
                .toList();
    }

    /**
     * 查询某航班的全部关闭影响（跨关闭事件，含处理结果）。
     */
    public List<ClosureImpactView> impactsOfFlight(String externalNo) {
        return impactRepository.findDetailByClearanceExternalNo(externalNo).stream()
                .map(this::toImpactView)
                .toList();
    }

    /**
     * 确认关闭替代航线。相同 rerouteNo + 相同内容：返回首次结果（幂等）。
     * 关闭范围版本、许可版本或航班状态变化时拒绝旧方案；
     * 新旧容量切换与影响标记处理在同一事务完成。
     */
    public ClosureRerouteOutcome confirmReroute(String eventNo, String externalNo,
                                                ClosureRerouteRequest request) {
        validate(request);

        com.chris64233.cc.airspace.domain.RouteVersion existing =
                routeVersionByRerouteNo(request.rerouteNo());
        if (existing != null) {
            if (!sameClosureRerouteContent(existing, eventNo, externalNo, request)) {
                throw new IdempotentConflictException(
                        "改道业务号 " + request.rerouteNo() + " 已存在但申请内容不同");
            }
            return new ClosureRerouteOutcome(clearanceService.get(externalNo), false);
        }

        try {
            var updated = clearanceTxService.confirmClosureReroute(eventNo, externalNo, request);
            return new ClosureRerouteOutcome(clearanceService.get(updated.getExternalNo()), true);
        } catch (DataIntegrityViolationException e) {
            com.chris64233.cc.airspace.domain.RouteVersion raced =
                    routeVersionByRerouteNo(request.rerouteNo());
            if (raced == null || !sameClosureRerouteContent(raced, eventNo, externalNo, request)) {
                throw new IdempotentConflictException(
                        "改道业务号 " + request.rerouteNo() + " 已存在但申请内容不同");
            }
            return new ClosureRerouteOutcome(clearanceService.get(externalNo), false);
        }
    }

    private com.chris64233.cc.airspace.domain.RouteVersion routeVersionByRerouteNo(String rerouteNo) {
        return routeVersionRepository.findDetailByRerouteNo(rerouteNo).orElse(null);
    }

    // ------------------------------------------------------------------

    private void validate(ClosureRequest request) {
        if (request.eventNo() == null || request.eventNo().isBlank()) {
            throw new ParamInvalidException("关闭事件号不能为空");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ParamInvalidException("关闭结束时间必须晚于开始时间");
        }
        if (request.segmentCodes() == null || request.segmentCodes().isEmpty()) {
            throw new ParamInvalidException("关闭区域航段不能为空");
        }
        for (String code : request.segmentCodes()) {
            segmentRepository.findByCode(code)
                    .orElseThrow(() -> new ParamInvalidException("航段不存在：" + code));
        }
    }

    private void validate(ClosureRerouteRequest request) {
        if (request.rerouteNo() == null || request.rerouteNo().isBlank()) {
            throw new ParamInvalidException("改道业务号不能为空");
        }
        if (request.effectiveAt() == null) {
            throw new ParamInvalidException("生效时间不能为空");
        }
        if (request.legs() == null || request.legs().isEmpty()) {
            throw new ParamInvalidException("替代航线不能为空");
        }
    }

    private boolean sameContent(AirspaceClosure closure, ClosureRequest request) {
        if (!closure.getStartTime().equals(request.startTime())
                || !closure.getEndTime().equals(request.endTime())) {
            return false;
        }
        List<String> stored = closure.getSegments().stream()
                .map(AirspaceClosureSegment::getSegment)
                .map(com.chris64233.cc.airspace.domain.AirSegment::getCode)
                .sorted()
                .toList();
        List<String> asked = request.segmentCodes().stream().sorted().distinct().toList();
        return stored.equals(asked);
    }

    private boolean sameClosureRerouteContent(com.chris64233.cc.airspace.domain.RouteVersion version,
                                              String eventNo, String externalNo,
                                              ClosureRerouteRequest request) {
        if (!eventNo.equals(version.getClosureEventNo())
                || !version.getClearance().getExternalNo().equals(externalNo)
                || version.getVersion() - 1 != request.expectedClearanceVersion()
                || !version.getEffectiveAt().equals(request.effectiveAt())) {
            return false;
        }
        if (request.fromLegIndex() != null && version.getFromLegIndex() != request.fromLegIndex()) {
            return false;
        }
        var legs = version.getLegs();
        if (legs.size() - version.getFromLegIndex() != request.legs().size()) {
            return false;
        }
        for (int i = 0; i < request.legs().size(); i++) {
            var stored = legs.get(version.getFromLegIndex() + i);
            var asked = request.legs().get(i);
            if (!stored.getSegment().getCode().equals(asked.segmentCode())
                    || stored.getAltitude() != asked.altitude()) {
                return false;
            }
        }
        return true;
    }

    private ClosureView toView(AirspaceClosure closure) {
        List<ClosureImpact> impacts = impactRepository.findDetailByEventNo(closure.getEventNo());
        Map<ClosureImpactStatus, Long> counts = impacts.stream()
                .collect(Collectors.groupingBy(ClosureImpact::getStatus, Collectors.counting()));
        List<String> codes = closure.getSegments().stream()
                .map(cs -> cs.getSegment().getCode())
                .toList();
        return new ClosureView(
                closure.getEventNo(),
                closure.getReason(),
                closure.getStartTime(),
                closure.getEndTime(),
                closure.getStatus().name(),
                closure.getScopeVersion(),
                closure.getCreatedAt(),
                closure.getCancelledAt(),
                codes,
                counts.getOrDefault(ClosureImpactStatus.PENDING, 0L),
                counts.getOrDefault(ClosureImpactStatus.HANDLED, 0L),
                counts.getOrDefault(ClosureImpactStatus.RELEASED, 0L),
                counts.getOrDefault(ClosureImpactStatus.EXPIRED, 0L));
    }

    private ClosureImpactView toImpactView(ClosureImpact impact) {
        String segmentCode = segmentRepository.findById(impact.getSegmentId())
                .map(com.chris64233.cc.airspace.domain.AirSegment::getCode)
                .orElse("?");
        return new ClosureImpactView(
                impact.getClosure().getEventNo(),
                impact.getClosure().getScopeVersion(),
                impact.getClearance().getExternalNo(),
                impact.getSegmentOrder(),
                segmentCode,
                impact.getStatus().name(),
                impact.getClearanceVersion(),
                impact.getFlownLegCount(),
                impact.getResolvedRerouteNo(),
                impact.getResolvedRouteVersion());
    }
}
