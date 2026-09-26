package com.chris64233.cc.airspace.web.dto;

public record LegConflictView(
        int order,
        String segmentCode,
        int capacity,
        long overlappingActiveCount) {
}
