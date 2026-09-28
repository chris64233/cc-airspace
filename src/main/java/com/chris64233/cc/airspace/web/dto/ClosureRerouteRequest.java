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
 * 关闭改道申请：针对某关闭事件为指定航班确认替代航线。
 *
 * @param fromLegIndex 改道起点航段序号；可空，默认取该航班最早的待处理受影响航段。
 *                     显式给出时必须不晚于最早受影响航段，且不得早于已飞航段数。
 */
public record ClosureRerouteRequest(
        @NotBlank @Size(max = 64) String rerouteNo,
        @NotNull @Min(1) Integer expectedClearanceVersion,
        @NotNull @Min(1) Integer expectedScopeVersion,
        @Min(0) Integer fromLegIndex,
        @NotNull Instant effectiveAt,
        @NotEmpty @Valid List<SubmitClearanceRequest.LegRequest> legs) {
}
