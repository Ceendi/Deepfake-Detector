package com.deepfake.orchestrator.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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

    private final ArtifactAuthorizationService authorization;
    private final BoundedArtifactResponseTransformer bodyTransformer;
    private final S3Client s3;
    private final String bucket;

    public ArtifactService(ArtifactAuthorizationService authorization, S3Client s3,
            @Value("${storage.artifacts-bucket}") String bucket,
            @Value("${storage.artifact-max-bytes:8388608}") int maxBytes) {
        this.authorization = authorization;
        this.bodyTransformer = new BoundedArtifactResponseTransformer(maxBytes);
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] download(UUID analysisId, String source, String name, String currentUserId) {
        // A separate Spring service proxy commits/releases authorization before remote I/O.
        String key = authorization.resolveOwnedKey(analysisId, source, name, currentUserId);

        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), bodyTransformer);
        } catch (NoSuchKeyException e) {
            // Recorded but missing in storage (e.g. retention sweep) — same 404 as never-existed.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (SdkException e) {
            log.warn("artifact fetch failed for {}/{}/{}: {}", analysisId, source, name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "artifact storage unavailable");
        }
    }

}
