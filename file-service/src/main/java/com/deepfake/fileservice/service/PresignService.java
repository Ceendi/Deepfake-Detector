package com.deepfake.fileservice.service;

import com.deepfake.fileservice.dto.PresignResponse;
import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PresignService {

    private static final Duration TTL = Duration.ofHours(1);

    private final FileMetadataRepository repository;
    private final S3Presigner presigner;
    @Value("${storage.bucket}") String bucket;

    @Transactional(readOnly = true)
    public PresignResponse presign(UUID fileId, String currentUserId) {
        FileMetadata m = repository.findByFileIdAndDeletedAtIsNull(fileId)
                // IDOR guard: 404 not 403, so a foreign (or soft-deleted) file looks like a missing one.
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(TTL)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket).key(m.getObjectKey()).build())
                .build();
        PresignedGetObjectRequest presigned = presigner.presignGetObject(request);
        // Do not log the URL — it carries the signature (a time-limited credential).
        return new PresignResponse(presigned.url().toString(), Instant.now().plus(TTL));
    }
}
