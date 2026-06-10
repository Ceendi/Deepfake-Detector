package com.deepfake.orchestrator.dto.response;

import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.entity.AnalysisStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight view of an analysis for the history list. Closed interface projection: Spring Data
 * selects only these columns, which are exactly the INCLUDE columns of idx_analysis_user_created
 * (V2) — so the per-user list can go index-only. videoProb/audioProb/errorMessage/fileKey are
 * intentionally absent (they belong to the detail view, GET /api/analysis/{id}).
 */
public interface AnalysisSummary {
    UUID getId();

    String getFileId();

    AnalysisType getType();

    AnalysisStatus getStatus();

    String getVerdict();

    BigDecimal getConfidence();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
