package com.deepfake.fileservice.service;

import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import org.springframework.beans.factory.annotation.Value;
import com.deepfake.fileservice.exception.StorageQuotaExceededException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
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

    public FileStorageService(JdbcTemplate jdbc, FileMetadataRepository repository, S3Client s3,
            @Value("${storage.bucket}") String bucket,
            @Value("${storage.quota-bytes:5368709120}") long quota) {
        if (quota <= 0) throw new IllegalArgumentException("Storage quota must be positive");
        this.jdbc = jdbc;
        this.repository = repository;
        this.s3 = s3;
        this.bucket = bucket;
        this.quota = quota;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void store(FileMetadata metadata, Path file) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            // Hold a per-owner lock until the metadata commits, including the S3 write.
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(114546, hashtext(?))")) {
                statement.setString(1, metadata.getUserId());
                statement.execute();
            }
            return null;
        });
        long used = repository.activeBytes(metadata.getUserId());
        if (metadata.getSizeBytes() < 0 || metadata.getSizeBytes() > quota - used) {
            throw new StorageQuotaExceededException();
        }
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(metadata.getObjectKey())
                .contentType(metadata.getMimetype()).contentLength(metadata.getSizeBytes())
                .metadata(Map.of("user-id", metadata.getUserId())).build(), RequestBody.fromFile(file));
        // A failed commit leaves an invisible object for the existing orphan sweep.
        repository.save(metadata);
    }
}
