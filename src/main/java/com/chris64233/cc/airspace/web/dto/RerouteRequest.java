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
 */
public record RerouteRequest(
        @NotBlank @Size(max = 64) String rerouteNo,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(0) Integer fromLegIndex,
        @NotNull Instant effectiveAt,
        @NotEmpty @Valid List<SubmitClearanceRequest.LegRequest> legs) {
}
