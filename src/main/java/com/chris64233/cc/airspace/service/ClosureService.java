package com.chris64233.cc.airspace.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.chris64233.cc.airspace.domain.AirspaceClosure;
import com.chris64233.cc.airspace.domain.ClosureImpact;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.ResourceNotFoundException;
import com.chris64233.cc.airspace.web.dto.ClosureImpactView;
import com.chris64233.cc.airspace.web.dto.ClosureView;
import com.chris64233.cc.airspace.web.dto.CreateClosureRequest;
import com.chris64233.cc.airspace.web.dto.ReviseClosureRequest;

/**
 * 临时空域关闭门面：参数校验、关闭事件号幂等处理、视图组装与查询。
 * 区域锁定、受影响航段标记、修订与取消的真正逻辑由 {@link ClosureTxService} 在事务中完成。
 */
@Service
public class ClosureService {

    public record CreateOutcome(ClosureView view, boolean created) {
    }

    private final ClosureTxService txService;
    private final AirspaceClosureRepository closureRepository;
    private final ClosureImpactRepository impactRepository;

    public ClosureService(ClosureTxService txService,
                          AirspaceClosureRepository closureRepository,
                          ClosureImpactRepository impactRepository) {
        this.txService = txService;
        this.closureRepository = closureRepository;
        this.impactRepository = impactRepository;
    }

    /**
     * 创建关闭事件并标记受影响航段。
     * 相同关闭事件号 + 相同内容：返回原事件（幂等）；相同号 + 不同内容：幂等冲突。
     */
    public CreateOutcome create(CreateClosureRequest request) {
        validate(request.startTime(), request.endTime(), request.segmentCodes());

        AirspaceClosure existing = closureRepository.findDetailByClosureNo(request.closureNo())
                .orElse(null);
        if (existing != null) {
            if (!sameContent(existing, request)) {
                throw new IdempotentConflictException(
                        "关闭事件号 " + request.closureNo() + " 已存在但事件内容不同");
            }
            return new CreateOutcome(toView(existing), false);
        }

        try {
            AirspaceClosure created = txService.create(request);
            return new CreateOutcome(toView(created), true);
        } catch (DataIntegrityViolationException e) {
            AirspaceClosure raced = closureRepository.findDetailByClosureNo(request.closureNo())
                    .orElseThrow(() -> e);
            if (!sameContent(raced, request)) {
                throw new IdempotentConflictException(
                        "关闭事件号 " + request.closureNo() + " 已存在但事件内容不同");
            }
            return new CreateOutcome(toView(raced), false);
        }
    }

    /**
     * 修订关闭范围 / 时段：范围版本 +1，尚未处理的受影响标记按新范围重算，
     * 引用旧范围版本的待执行改道方案将被拒绝。
     */
    public ClosureView revise(String closureNo, ReviseClosureRequest request) {
        validate(request.startTime(), request.endTime(), request.segmentCodes());
        return toView(txService.revise(closureNo, request));
    }

    /**
     * 取消关闭事件：只解除尚未处理航班的限制，已完成的改道不自动撤销。重复取消幂等。
     */
    public ClosureView cancel(String closureNo) {
        return toView(txService.cancel(closureNo));
    }

    public ClosureView get(String closureNo) {
        AirspaceClosure closure = closureRepository.findDetailByClosureNo(closureNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + closureNo));
        return toView(closure);
    }

    /** 全部关闭事件（含已取消），按主键升序。 */
    public List<ClosureView> list() {
        return closureRepository.findAllByOrderById().stream().map(c -> get(c.getClosureNo())).toList();
    }

    /**
     * 查询某关闭事件的受影响航班与逐航段处理结果（PENDING / REROUTED），按标记顺序升序。
     */
    public List<ClosureImpactView> impacts(String closureNo) {
        closureRepository.findByClosureNo(closureNo)
                .orElseThrow(() -> new ResourceNotFoundException("关闭事件不存在：" + closureNo));
        return impactRepository.findDetailByClosureNo(closureNo).stream()
                .map(this::toImpactView)
                .toList();
    }

    /**
     * 查询某航班当前挂在哪些关闭事件下（含已处理与未处理标记），按标记顺序升序。
     */
    public List<ClosureImpactView> impactsForFlight(String externalNo) {
        return impactRepository.findDetailByClearanceExternalNo(externalNo).stream()
                .map(this::toImpactView)
                .toList();
    }

    private void validate(Instant startTime, Instant endTime, List<String> segmentCodes) {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) {
            throw new ParamInvalidException("关闭结束时间必须晚于开始时间");
        }
        if (segmentCodes == null || segmentCodes.isEmpty()) {
            throw new ParamInvalidException("关闭区域航段列表不能为空");
        }
    }

    private boolean sameContent(AirspaceClosure closure, CreateClosureRequest request) {
        if (!Objects.equals(closure.getReason(), request.reason())
                || !closure.getStartTime().equals(request.startTime())
                || !closure.getEndTime().equals(request.endTime())) {
            return false;
        }
        List<String> storedCodes = closure.getSegments().stream()
                .map(s -> s.getSegment().getCode())
                .toList();
        if (storedCodes.size() != request.segmentCodes().size()) {
            return false;
        }
        for (int i = 0; i < storedCodes.size(); i++) {
            if (!storedCodes.get(i).equals(request.segmentCodes().get(i))) {
                return false;
            }
        }
        return true;
    }

    private ClosureView toView(AirspaceClosure closure) {
        return new ClosureView(
                closure.getClosureNo(),
                closure.getReason(),
                closure.getStartTime(),
                closure.getEndTime(),
                closure.getStatus().name(),
                closure.getScopeVersion(),
                closure.getCreatedAt(),
                closure.getCancelledAt(),
                closure.getSegments().stream().map(s -> s.getSegment().getCode()).toList());
    }

    private ClosureImpactView toImpactView(ClosureImpact impact) {
        return new ClosureImpactView(
                impact.getClosure().getClosureNo(),
                impact.getClearance().getExternalNo(),
                impact.getLegOrder(),
                impact.getSegment().getCode(),
                impact.getStatus().name(),
                impact.getScopeVersionAtMark(),
                impact.getResolvedRerouteNo(),
                impact.getCreatedAt(),
                impact.getResolvedAt());
    }
}
