package com.chris64233.cc.airspace.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SegmentUpsertRequest(
        @NotBlank @Size(max = 64) String code,
        @NotNull @Min(0) Integer minAltitude,
        @NotNull @Min(0) Integer maxAltitude,
        @NotNull @Min(1) Integer capacity) {
}
