package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

public record ClearanceView(
        String externalNo,
        String aircraft,
        Instant startTime,
        Instant endTime,
        String status,
        Instant createdAt,
        Instant cancelledAt,
        List<LegOccupancyView> legs) {
}
