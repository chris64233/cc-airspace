package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

public record RouteVersionView(
        int version,
        String rerouteNo,
        String closureNo,
        Integer closureScopeVersion,
        int fromLegIndex,
        Instant effectiveAt,
        Instant createdAt,
        List<RouteLegView> legs) {
}
