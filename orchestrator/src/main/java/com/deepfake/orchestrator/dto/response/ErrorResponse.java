package com.deepfake.orchestrator.dto.response;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform error body. {@code fields} is populated only for validation failures (otherwise
 * omitted). {@code correlationId} will be propagated from the request header in week 4.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        Map<String, String> fields,
        String correlationId,
        Instant timestamp) {
}
