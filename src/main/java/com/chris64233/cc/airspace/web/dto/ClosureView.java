package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

public record ClosureView(
        String eventNo,
        String reason,
        Instant startTime,
        Instant endTime,
        String status,
        int scopeVersion,
        Instant createdAt,
        Instant cancelledAt,
        List<String> segmentCodes,
        long pendingCount,
        long handledCount,
        long releasedCount,
        long expiredCount) {
}
