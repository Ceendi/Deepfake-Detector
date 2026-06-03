package com.deepfake.orchestrator.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/**
 * API view of {@link Analysis} — never expose the JPA entity (leaks internal state, risks
 * lazy-proxy serialization). Shape per docs/contracts/rest-api.md. {@code details} is null in
 * MVP (later: Grad-CAM URLs/metadata).
 */
public record AnalysisResponse(
        UUID id,
        String userId,
        String fileId,
        String fileKey,
        AnalysisType type,
        AnalysisStatus status,
        String verdict,
        BigDecimal confidence,
        BigDecimal videoProb,
        BigDecimal audioProb,
        Map<String, Object> details,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public static AnalysisResponse from(Analysis a) {
        return new AnalysisResponse(
                a.getId(),
                a.getUserId(),
                a.getFileId(),
                a.getFileKey(),
                a.getType(),
                a.getStatus(),
                a.getVerdict(),
                a.getConfidence(),
                a.getVideoProb(),
                a.getAudioProb(),
                null,
                a.getErrorMessage(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
