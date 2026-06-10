package com.deepfake.orchestrator.dto.sse;

import java.math.BigDecimal;

import com.deepfake.orchestrator.entity.Analysis;

/** SSE {@code result} event payload — the terminal state pushed when an analysis finishes. */
public record AnalysisResultEvent(
        String analysisId,
        String status,
        String verdict,
        BigDecimal confidence) {

    public static AnalysisResultEvent of(Analysis a) {
        return new AnalysisResultEvent(
                a.getId().toString(),
                a.getStatus().name(),
                a.getVerdict(),
                a.getConfidence());
    }
}
