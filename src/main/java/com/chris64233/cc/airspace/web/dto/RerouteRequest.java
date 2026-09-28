package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 改道申请：引用许可当前版本与衔接位置，给出替换尾部的新有序航段与生效时间。
 * 新航线 = 当前航线 [0, fromLegIndex) 前缀 + legs。
 *
 * 可选的 closureNo / expectedClosureVersion 用于响应临时空域关闭：
 * 引用的关闭事件已取消、范围版本已变化（expectedClosureVersion 过期）、
 * 航班不再受其影响，或新航线仍穿越关闭区域时，方案作为旧方案被拒绝。
 */
public record RerouteRequest(
        @NotBlank @Size(max = 64) String rerouteNo,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(0) Integer fromLegIndex,
        @NotNull Instant effectiveAt,
        @NotEmpty @Valid List<SubmitClearanceRequest.LegRequest> legs,
        @Size(max = 64) String closureNo,
        @Min(1) Integer expectedClosureVersion) {

    /** 普通改道（不引用关闭事件）的便捷构造器。 */
    public RerouteRequest(String rerouteNo, Integer expectedVersion, Integer fromLegIndex,
                          Instant effectiveAt, List<SubmitClearanceRequest.LegRequest> legs) {
        this(rerouteNo, expectedVersion, fromLegIndex, effectiveAt, legs, null, null);
    }

    public boolean referencesClosure() {
        return closureNo != null && !closureNo.isBlank();
    }
}
