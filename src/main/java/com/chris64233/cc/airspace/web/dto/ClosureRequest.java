package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 临时空域关闭事件申请：指定关闭区域（航段代码集合）与关闭时段 [startTime, endTime)。
 * 同一 eventNo 也用于范围调整。
 */
public record ClosureRequest(
        @NotBlank @Size(max = 64) String eventNo,
        @Size(max = 256) String reason,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotEmpty List<@NotBlank @Size(max = 64) String> segmentCodes) {
}
