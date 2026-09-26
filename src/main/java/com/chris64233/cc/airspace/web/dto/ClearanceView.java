package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

public record ClearanceView(
        String externalNo,
        String aircraft,
        Instant startTime,
        Instant endTime,
        String status,
        int version,
        int flownLegCount,
        Instant createdAt,
        Instant startedAt,
        Instant cancelledAt,
        List<LegOccupancyView> legs) {
}
