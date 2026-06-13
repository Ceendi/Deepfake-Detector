package com.deepfake.orchestrator.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/**
 * API view of {@link Analysis} — never expose the JPA entity (leaks internal state, risks
 * lazy-proxy serialization). Shape per docs/contracts/rest-api.md. {@code details} groups the
 * per-source detector results ({@code video} / {@code audio}: modelVersion, gradcamKeys,
 * metadata); null until a detector reports.
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
                details(a),
                a.getErrorMessage(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }

    private static Map<String, Object> details(Analysis a) {
        if (a.getVideoDetails() == null && a.getAudioDetails() == null) {
            return null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (a.getVideoDetails() != null) {
            details.put("video", withArtifactUrls(a.getId(), "video", a.getVideoDetails()));
        }
        if (a.getAudioDetails() != null) {
            details.put("audio", withArtifactUrls(a.getId(), "audio", a.getAudioDetails()));
        }
        return details;
    }

    // Clients consume artifact-endpoint URLs, not raw bucket keys — the storage layout stays
    // internal. Keys are kept alongside for audit; the URL filename is the key's last segment,
    // which is exactly what the membership check in ArtifactService matches against.
    private static Map<String, Object> withArtifactUrls(UUID id, String source, Map<String, Object> stored) {
        if (!(stored.get("gradcamKeys") instanceof Collection<?> keys) || keys.isEmpty()) {
            return stored;
        }
        Map<String, Object> copy = new LinkedHashMap<>(stored);
        copy.put("gradcamUrls", keys.stream().map(Object::toString)
                .map(k -> "/api/analysis/" + id + "/artifacts/" + source + "/"
                        + k.substring(k.lastIndexOf('/') + 1))
                .toList());
        return copy;
    }
}
