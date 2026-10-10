package com.deepfake.orchestrator.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/** Durable cleanup and conservative orphan discovery, confined to the artifact bucket. */
@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ConditionalOnProperty(name = "reliability.artifact-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ArtifactCleanupWorker {
    private static final Pattern ATTEMPT = Pattern.compile(
            "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/(?:audio/gradcam_[0-9a-f]{32}|video/gradcam_frame_[0-9]{2,}_[0-9a-f]{32})\\.png$");
    private final ArtifactCleanupStore store;
    private final S3Client s3;
    private final String bucket;
    private final int batchSize;
    private final int pageSize;
    private final Duration orphanAge;

    public ArtifactCleanupWorker(ArtifactCleanupStore store, S3Client s3,
            @Value("${storage.artifacts-bucket}") String bucket,
            @Value("${reliability.artifact-cleanup.batch-size:10}") int batchSize,
            @Value("${reliability.artifact-cleanup.page-size:100}") int pageSize,
            @Value("${reliability.artifact-cleanup.orphan-age:24h}") Duration orphanAge) {
        if (!"analysis-artifacts".equals(bucket) || batchSize < 1 || batchSize > 100 || pageSize < 1
                || pageSize > 1000 || orphanAge.isZero() || orphanAge.isNegative()) {
            throw new IllegalArgumentException("Cleanup requires analysis-artifacts, bounded batch/page sizes and positive orphan age");
        }
        this.store = store; this.s3 = s3; this.bucket = bucket;
        this.batchSize = batchSize; this.pageSize = pageSize; this.orphanAge = orphanAge;
    }

    @Scheduled(scheduler = "artifactCleanupScheduler", fixedDelayString = "${reliability.artifact-cleanup.poll-interval-ms:1000}")
    public void clean() {
        for (int i = 0; i < batchSize; i++) {
            ArtifactCleanupStore.Work work = store.claim();
            if (work == null) return;
            if (work.token() == null) continue; // protected work removed without remote I/O
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(work.key()).build());
                store.complete(work);
            } catch (RuntimeException failure) {
                // A crash after S3 success but before completion is safe: DeleteObject is idempotent.
                store.retry(work);
                log.warn("artifact cleanup deferred for {}: {}", work.key(), failure.getMessage());
            }
        }
    }

    @Scheduled(scheduler = "artifactCleanupScheduler", fixedDelayString = "${reliability.artifact-cleanup.scan-interval-ms:60000}")
    public void scan() {
        ArtifactCleanupStore.Scan scan = store.claimScan();
        if (scan == null) return;
        try {
            var page = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket)
                    .maxKeys(pageSize).continuationToken(scan.continuationToken()).build());
            if (Boolean.TRUE.equals(page.isTruncated()) && (page.nextContinuationToken() == null
                    || page.nextContinuationToken().equals(scan.continuationToken()))) {
                throw new IllegalStateException("Truncated artifact page has no advancing continuation token");
            }
            Instant cutoff = Instant.now().minus(orphanAge);
            var candidates = new ArrayList<ArtifactCleanupStore.Candidate>();
            for (var object : page.contents()) {
                var match = ATTEMPT.matcher(object.key());
                if (object.lastModified() != null && object.lastModified().isBefore(cutoff) && match.matches()) {
                    candidates.add(new ArtifactCleanupStore.Candidate(object.key(), UUID.fromString(match.group(1))));
                }
            }
            store.finishScan(scan, candidates, Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null);
        } catch (RuntimeException failure) {
            store.releaseScan(scan); // preserve cursor on outages; retry the same page on the next run
            log.warn("artifact orphan scan deferred: {}", failure.getMessage());
        }
    }
}
