package com.deepfake.fileservice.service;

import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import org.springframework.beans.factory.annotation.Value;
import com.deepfake.fileservice.exception.StorageQuotaExceededException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.util.Map;

@Service
public class FileStorageService {
    private final JdbcTemplate jdbc;
    private final FileMetadataRepository repository;
    private final S3Client s3;
    private final String bucket;
    private final long quota;
    private final TransactionTemplate admission;

    public FileStorageService(JdbcTemplate jdbc, FileMetadataRepository repository, S3Client s3,
            PlatformTransactionManager transactions,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.quota-bytes:5368709120}") long quota) {
        if (quota <= 0) throw new IllegalArgumentException("Storage quota must be positive");
        this.jdbc = jdbc;
        this.admission = new TransactionTemplate(transactions);
        this.admission.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.admission.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.repository = repository;
        this.s3 = s3;
        this.bucket = bucket;
        this.quota = quota;
    }

    /**
     * Writes the object first and only then admits it under a short per-owner lock. Holding the
     * lock (and a pooled connection) across the S3 write serialized every upload of one owner
     * across all replicas and could exhaust the pool.
     */
    public void store(FileMetadata metadata, Path file) {
        // Cheap early rejection; the authoritative check runs again under the lock.
        if (exceedsQuota(metadata)) throw new StorageQuotaExceededException();
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(metadata.getObjectKey())
                .contentType(metadata.getMimetype()).contentLength(metadata.getSizeBytes())
                .metadata(Map.of("user-id", metadata.getUserId())).build(), RequestBody.fromFile(file));
        try {
            admission.executeWithoutResult(status -> {
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(114546, hashtext(?))")) {
                        statement.setString(1, metadata.getUserId());
                        statement.execute();
                    }
                    return null;
                });
                if (exceedsQuota(metadata)) throw new StorageQuotaExceededException();
                repository.save(metadata);
            });
        } catch (RuntimeException rejected) {
            // A failed delete or commit leaves an unreferenced object for the existing orphan sweep.
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(metadata.getObjectKey()).build());
            } catch (RuntimeException cleanup) {
                rejected.addSuppressed(cleanup);
            }
            throw rejected;
        }
    }

    private boolean exceedsQuota(FileMetadata metadata) {
        return metadata.getSizeBytes() < 0 || metadata.getSizeBytes() > quota - repository.storedBytes(metadata.getUserId());
    }
}
