package com.chris64233.cc.airspace.web.dto;

public record LegOccupancyView(
        int order,
        String segmentCode,
        int altitude,
        int capacity,
        long overlappingActiveCount) {
}
