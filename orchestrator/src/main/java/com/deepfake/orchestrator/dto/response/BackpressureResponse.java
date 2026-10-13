package com.deepfake.orchestrator.dto.response;

import com.deepfake.orchestrator.exception.TooManyAnalysesException.Reason;
import io.swagger.v3.oas.annotations.media.Schema;

public record BackpressureResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Reason reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int limit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int queuePosition,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int retryAfterSeconds) {
}
