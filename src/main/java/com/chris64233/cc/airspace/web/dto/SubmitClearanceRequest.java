package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SubmitClearanceRequest(
        @NotBlank @Size(max = 64) String externalNo,
        @NotBlank @Size(max = 64) String aircraft,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotEmpty @Valid List<LegRequest> legs) {

    public record LegRequest(
            @NotBlank @Size(max = 64) String segmentCode,
            @NotNull @Positive Integer altitude) {
    }
}
