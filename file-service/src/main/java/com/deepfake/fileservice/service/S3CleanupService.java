package com.deepfake.fileservice.service;

import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Periodic file lifecycle sweep over {@code deepfake-uploads}:
 *
 * <ol>
 * <li><b>Purge</b> — soft-deleted rows older than the retention window lose their S3 object and
 * are hard-deleted. S3 first, row second: if either step fails the row survives and the next
 * sweep retries (DeleteObject is idempotent).</li>
 * <li><b>Orphan reconcile</b> — objects with no metadata row (upload wrote S3 but the row commit
 * failed) are reclaimed once older than a safety age, so an in-flight upload between its S3 put
 * and row insert is never swept.</li>
 * </ol>
 *
 * Safe under D4 replication (×2): every step is idempotent, and the row hard-delete is the CAS —
 * only the replica that wins it increments {@code files_purged_total}, so concurrent sweeps don't
 * double-count. Failures are per-item: one bad object never aborts the rest of the sweep.
 */
@Slf4j
@Service
public class S3CleanupService {

    private final FileMetadataRepository repository;
    private final S3Client s3;
    private final String bucket;
    private final Duration retention;
    private final Duration orphanMinAge;
    private final Counter purged;
    private final Counter orphansReclaimed;

    public S3CleanupService(FileMetadataRepository repository, S3Client s3,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.cleanup.retention-hours:72}") long retentionHours,
            @Value("${storage.cleanup.orphan-min-age-hours:24}") long orphanMinAgeHours,
            MeterRegistry registry) {
        this.repository = repository;
        this.s3 = s3;
        this.bucket = bucket;
        this.retention = Duration.ofHours(retentionHours);
        this.orphanMinAge = Duration.ofHours(orphanMinAgeHours);
        this.purged = Counter.builder("files.purged")
                .description("Soft-deleted files purged from S3 + DB after the retention window")
                .register(registry);
        this.orphansReclaimed = Counter.builder("files.orphans.reclaimed")
                .description("Orphaned S3 objects (no metadata row) reclaimed by the cleanup sweep")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${storage.cleanup.scan-interval-ms:3600000}",
            initialDelayString = "${storage.cleanup.initial-delay-ms:60000}")
    public void sweep() {
        purgeSoftDeleted();
        reclaimOrphans();
    }

    void purgeSoftDeleted() {
        List<FileMetadata> expired = repository.findByDeletedAtBefore(Instant.now().minus(retention));
        for (FileMetadata m : expired) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(m.getObjectKey()).build());
                if (repository.hardDeleteIfSoftDeleted(m.getFileId()) == 1) {
                    purged.increment();
                    log.info("purged expired file {} ({})", m.getFileId(), m.getObjectKey());
                }
            } catch (Exception e) {
                log.error("purge failed for {}, retrying next sweep: {}", m.getFileId(), e.toString());
            }
        }
    }

    void reclaimOrphans() {
        Instant cutoff = Instant.now().minus(orphanMinAge);
        try {
            String token = null;
            do {
                ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket).continuationToken(token).build());
                reclaimOrphansInPage(page, cutoff);
                token = page.nextContinuationToken();
            } while (token != null);
        } catch (Exception e) {
            // Listing failed (S3 down) — fail open, the purge path already ran this sweep.
            log.error("orphan sweep aborted, retrying next sweep: {}", e.toString());
        }
    }

    private void reclaimOrphansInPage(ListObjectsV2Response page, Instant cutoff) {
        List<S3Object> aged = page.contents().stream()
                .filter(o -> o.lastModified().isBefore(cutoff))
                .toList();
        if (aged.isEmpty()) {
            return;
        }
        // One IN-query per page (max 1000 keys). Soft-deleted rows still count as known —
        // their objects belong to the purge path, not the orphan path.
        Set<String> known = repository.findExistingObjectKeys(aged.stream().map(S3Object::key).toList());
        for (S3Object o : aged) {
            if (known.contains(o.key())) {
                continue;
            }
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(o.key()).build());
                orphansReclaimed.increment();
                log.warn("reclaimed orphaned object {} (no metadata row)", o.key());
            } catch (Exception e) {
                log.error("orphan delete failed for {}, retrying next sweep: {}", o.key(), e.toString());
            }
        }
    }
}
