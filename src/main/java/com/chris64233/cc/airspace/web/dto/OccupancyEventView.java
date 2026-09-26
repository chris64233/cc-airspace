package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;

public record OccupancyEventView(
        String segmentCode,
        String eventType,
        int altitude,
        int routeVersion,
        String rerouteNo,
        Instant occurredAt) {
}
