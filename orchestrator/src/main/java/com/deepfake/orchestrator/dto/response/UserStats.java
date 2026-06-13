package com.deepfake.orchestrator.dto.response;

import java.time.Instant;

/**
 * Flat carrier for the single-row stats aggregate in AnalysisRepository (JPQL constructor
 * expressions can't build nested records). Wire shape is {@link UserStatsResponse}.
 */
public record UserStats(
        long total,
        long completed,
        long failed,
        long cancelled,
        long inProgress,
        long video,
        long audio,
        long full,
        long fake,
        long real,
        Double avgConfidence,
        long last7Days,
        Instant lastAnalysisAt) {
}
