package com.deepfake.fileservice.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Uniform error body; {@code fields} is populated only for validation failures. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> fields,
        String correlationId,
        Instant timestamp) {
}
