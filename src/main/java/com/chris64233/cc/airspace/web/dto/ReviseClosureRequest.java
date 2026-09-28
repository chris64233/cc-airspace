package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修订关闭事件：用新的区域与时段整体替换，关闭范围版本 +1；
 * 已完成的改道不受影响，尚未处理的限制按新范围重算。
 */
public record ReviseClosureRequest(
        @Size(max = 256) String reason,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotEmpty List<@NotBlank @Size(max = 64) String> segmentCodes) {
}
