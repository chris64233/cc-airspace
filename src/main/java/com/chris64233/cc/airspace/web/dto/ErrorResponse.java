package com.chris64233.cc.airspace.web.dto;

import java.time.Instant;

public record ErrorResponse(String type, String message, Instant timestamp) {
}
