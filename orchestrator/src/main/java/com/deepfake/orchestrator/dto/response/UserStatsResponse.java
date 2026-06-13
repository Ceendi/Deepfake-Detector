package com.deepfake.orchestrator.dto.response;

import java.time.Instant;

/**
 * Aggregate stats of one user's analyses for the homepage dashboard. Shape per
 * docs/contracts/rest-api.md. {@code avgConfidence} averages COMPLETED analyses only (confidence
 * is null otherwise) and is null until the first one completes; {@code lastAnalysisAt} is null for
 * a user with no analyses.
 */
public record UserStatsResponse(
        long total,
        StatusCounts byStatus,
        TypeCounts byType,
        VerdictCounts verdicts,
        Double avgConfidence,
        long last7Days,
        Instant lastAnalysisAt) {

    public record StatusCounts(long completed, long failed, long cancelled, long inProgress) {
    }

    public record TypeCounts(long video, long audio, long full) {
    }

    public record VerdictCounts(long fake, long real) {
    }

    public static UserStatsResponse from(UserStats s) {
        return new UserStatsResponse(
                s.total(),
                new StatusCounts(s.completed(), s.failed(), s.cancelled(), s.inProgress()),
                new TypeCounts(s.video(), s.audio(), s.full()),
                new VerdictCounts(s.fake(), s.real()),
                s.avgConfidence(),
                s.last7Days(),
                s.lastAnalysisAt());
    }
}
