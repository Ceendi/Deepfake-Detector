package com.deepfake.orchestrator.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.repository.AnalysisRepository;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Serves Grad-CAM artifacts from the private analysis-artifacts bucket. Authorization is
 * by membership: the requested name must match one of the gradcamKeys the orchestrator
 * persisted from the detector result — the S3 key is never built from request input, so
 * traversal/enumeration of the bucket is impossible by construction. IDOR → 404 (never 403).
 */
@Slf4j
@Service
public class ArtifactService {

    private final AnalysisRepository repository;
    private final S3Client s3;
    private final String bucket;

    public ArtifactService(AnalysisRepository repository, S3Client s3,
            @Value("${storage.artifacts-bucket}") String bucket) {
        this.repository = repository;
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Transactional(readOnly = true)
    public byte[] download(UUID analysisId, String source, String name, String currentUserId) {
        Analysis a = repository.findById(analysisId)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String key = storedKey(a, source, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
        } catch (NoSuchKeyException e) {
            // Recorded but missing in storage (e.g. retention sweep) — same 404 as never-existed.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (SdkException e) {
            log.warn("artifact fetch failed for {}/{}/{}: {}", analysisId, source, name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "artifact storage unavailable");
        }
    }

    private Optional<String> storedKey(Analysis a, String source, String name) {
        Map<String, Object> details = switch (source) {
            case "video" -> a.getVideoDetails();
            case "audio" -> a.getAudioDetails();
            default -> null;
        };
        if (details == null || !(details.get("gradcamKeys") instanceof Collection<?> keys)) {
            return Optional.empty();
        }
        // Match on the filename part so legacy keys without the {source}/ segment stay servable.
        return keys.stream().map(Object::toString)
                .filter(k -> name.equals(k.substring(k.lastIndexOf('/') + 1)))
                .findFirst();
    }
}
