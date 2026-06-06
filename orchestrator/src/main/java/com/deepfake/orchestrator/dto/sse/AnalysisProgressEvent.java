package com.deepfake.orchestrator.dto.sse;

/** SSE {@code progress} event payload (camelCase; AMQP snake_case is mapped in the service). */
public record AnalysisProgressEvent(
        String analysisId,
        String source,
        Integer progress,
        String stage,
        String status) {
}
