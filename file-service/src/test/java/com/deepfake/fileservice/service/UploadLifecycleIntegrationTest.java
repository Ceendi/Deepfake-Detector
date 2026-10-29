package com.deepfake.fileservice.service;

import com.deepfake.fileservice.entity.FileMetadata;
import com.deepfake.fileservice.exception.StorageQuotaExceededException;
import com.deepfake.fileservice.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "storage.quota-bytes=10")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({FileStorageService.class, FileMetadataService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class UploadLifecycleIntegrationTest {
    @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired FileMetadataRepository repository;
    @Autowired FileStorageService storage;
    @Autowired FileMetadataService metadata;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean S3Client s3;
    @TempDir Path tmp;
    Path media;

    @BeforeEach void setup() throws Exception {
        repository.deleteAll();
        reset(s3);
        media = Files.write(tmp.resolve("media"), new byte[10]);
    }

    private FileMetadata file(String owner, long size) {
        UUID id = UUID.randomUUID();
        return FileMetadata.builder().fileId(id).userId(owner).objectKey(id + ".mp4")
                .originalName("clip.mp4").sizeBytes(size).mimetype("video/mp4").build();
    }

    @Test void lostCommitResponseMustNotDeleteTheCommittedObject() {
        var uncertain = new org.springframework.transaction.PlatformTransactionManager() {
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return transactions.getTransaction(definition);
            }
            public void commit(org.springframework.transaction.TransactionStatus status) {
                transactions.commit(status);
                throw new org.springframework.transaction.TransactionSystemException("commit response lost");
            }
            public void rollback(org.springframework.transaction.TransactionStatus status) {
                transactions.rollback(status);
            }
        };
        var service = new FileStorageService(jdbc, repository, s3, uncertain, "deepfake-uploads", 10);
        var upload = file("alice", 10);
        assertThatThrownBy(() -> service.store(upload, media))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(repository.findById(upload.getFileId())).isPresent();
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void quotaIncludesOnlyOwnedRowsAndAcceptsExactBoundary() {
        repository.save(file("bob", 10));
        storage.store(file("alice", 10), media);
        assertThat(repository.storedBytes("alice")).isEqualTo(10);
        assertThatThrownBy(() -> storage.store(file("alice", 1), media))
                .isInstanceOf(StorageQuotaExceededException.class);
        verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test void softDeletedFilesCountUntilPurged() {
        var removed = repository.save(file("alice", 9));
        metadata.softDelete(removed.getFileId(), "alice");
        assertThatThrownBy(() -> storage.store(file("alice", 2), media))
                .isInstanceOf(StorageQuotaExceededException.class);
        repository.hardDeleteIfSoftDeleted(removed.getFileId());
        storage.store(file("alice", 10), media);
        assertThat(repository.storedBytes("alice")).isEqualTo(10);
    }

    @Test void simultaneousUploadsCannotBothSpendTheSameRemainingQuota() throws Exception {
        CountDownLatch bothInS3 = new CountDownLatch(2);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            bothInS3.countDown();
            assertThat(bothInS3.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> admitted(file("alice", 6)));
            var second = pool.submit(() -> admitted(file("alice", 6)));
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(repository.storedBytes("alice")).isEqualTo(6);
        assertThat(repository.count()).isEqualTo(1);
        // The losing upload's object is removed rather than left for the orphan sweep.
        verify(s3, times(1)).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void ownerUploadsDoNotWaitForEachOthersStorageWrite() throws Exception {
        CountDownLatch firstInS3 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            if (writes.getAndIncrement() == 0) {
                firstInS3.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var slow = pool.submit(() -> storage.store(file("alice", 3), media));
            assertThat(firstInS3.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                // Before the fix the owner lock was held across the write, so this waited for it.
                pool.submit(() -> storage.store(file("alice", 3), media)).get(5, TimeUnit.SECONDS);
                assertThat(repository.storedBytes("alice")).isEqualTo(3);
            } finally {
                release.countDown();
            }
            slow.get(10, TimeUnit.SECONDS);
        }
        assertThat(repository.storedBytes("alice")).isEqualTo(6);
    }

    private boolean admitted(FileMetadata upload) {
        try {
            storage.store(upload, media);
            return true;
        } catch (StorageQuotaExceededException quotaExceeded) {
            return false;
        }
    }

    @Test void failedStorageWriteRollsBackAndDoesNotConsumeQuota() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("storage outage"));
        assertThatThrownBy(() -> storage.store(file("alice", 10), media)).isInstanceOf(IllegalStateException.class);
        assertThat(repository.storedBytes("alice")).isZero();
        reset(s3);
        storage.store(file("alice", 10), media);
        assertThat(repository.storedBytes("alice")).isEqualTo(10);
    }

    @Test void claimIsOwnerScopedIdempotentAndPreservesLegacyObjectKey() {
        var owned = file("alice", 1);
        owned.setObjectKey("legacy/name.mp4");
        repository.save(owned);
        assertThatThrownBy(() -> metadata.claim(owned.getFileId(), "bob"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThat(repository.findById(owned.getFileId()).orElseThrow().getAnalyzedAt()).isNull();
        assertThat(metadata.claim(owned.getFileId(), "alice").objectKey()).isEqualTo("legacy/name.mp4");
        Instant first = repository.findById(owned.getFileId()).orElseThrow().getAnalyzedAt();
        metadata.claim(owned.getFileId(), "alice");
        assertThat(repository.findById(owned.getFileId()).orElseThrow().getAnalyzedAt()).isEqualTo(first);
        metadata.softDelete(owned.getFileId(), "alice");
        assertThatThrownBy(() -> metadata.claim(owned.getFileId(), "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThatThrownBy(() -> metadata.claim(UUID.randomUUID(), "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test void retentionExpiresOnlyOldUnusedUploadsAndClaimCannotReviveExpiredOne() {
        Instant now = Instant.now();
        var unused = repository.save(file("alice", 1));
        var claimed = repository.save(file("alice", 1));
        var fresh = repository.save(file("alice", 1));
        jdbc.update("UPDATE file_metadata SET created_at = ? WHERE file_id IN (?, ?)",
                java.sql.Timestamp.from(now.minusSeconds(8 * 86400L)), unused.getFileId(), claimed.getFileId());
        metadata.claim(claimed.getFileId(), "alice");
        assertThat(repository.expireUnused(now.minusSeconds(7 * 86400L), now)).isEqualTo(1);
        assertThat(repository.findById(unused.getFileId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(repository.findById(claimed.getFileId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(repository.findById(fresh.getFileId()).orElseThrow().getDeletedAt()).isNull();
        assertThatThrownBy(() -> metadata.claim(unused.getFileId(), "alice"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        assertThat(repository.expireUnused(now.minusSeconds(7 * 86400L), now)).isZero();
    }
}
