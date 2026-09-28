package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建临时空域关闭事件：区域由航段代码列表给出，时段为 [startTime, endTime)。
 */
public record CreateClosureRequest(
        @NotBlank @Size(max = 64) String closureNo,
        @Size(max = 256) String reason,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotEmpty List<@NotBlank @Size(max = 64) String> segmentCodes) {
}
