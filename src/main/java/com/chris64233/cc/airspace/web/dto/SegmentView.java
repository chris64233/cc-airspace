package com.chris64233.cc.airspace.web.dto;

import com.chris64233.cc.airspace.domain.AirSegment;

public record SegmentView(Long id, String code, int minAltitude, int maxAltitude, int capacity) {

    public static SegmentView from(AirSegment segment) {
        return new SegmentView(segment.getId(), segment.getCode(), segment.getMinAltitude(),
                segment.getMaxAltitude(), segment.getCapacity());
    }
}
