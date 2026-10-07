package com.deepfake.orchestrator.dto.sse;

import java.math.BigDecimal;
import java.util.UUID;

import com.deepfake.orchestrator.entity.AnalysisStatus;

/** An owner-scoped, committed scalar snapshot, independent of managed Analysis instances. */
public record AnalysisStreamSnapshot(UUID id, AnalysisStatus status, String verdict, BigDecimal confidence) {
    public AnalysisResultEvent result() {
        return new AnalysisResultEvent(id.toString(), status.name(), verdict, confidence);
    }
}
