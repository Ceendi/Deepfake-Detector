package com.deepfake.fileservice.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.fileservice.entity.FileMetadata;

/**
 * Persists and reads file_metadata against the real migrated schema (Testcontainers + Flyway,
 * ddl-auto: none), and confirms the active-only lookup hides soft-deleted rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class FileMetadataRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    FileMetadataRepository repository;
    @Autowired
    TestEntityManager em;

    @Test
    void savesAndReadsBackByFileId() {
        UUID fileId = UUID.randomUUID();
        repository.save(FileMetadata.builder()
                .fileId(fileId).objectKey(fileId + "_clip.mp4").userId("user-a")
                .originalName("clip.mp4").mimetype("video/mp4").sizeBytes(10_485_760L).build());
        em.flush();
        em.clear();

        Optional<FileMetadata> found = repository.findByFileIdAndDeletedAtIsNull(fileId);

        assertThat(found).isPresent();
        FileMetadata m = found.get();
        assertThat(m.getUserId()).isEqualTo("user-a");
        assertThat(m.getObjectKey()).isEqualTo(fileId + "_clip.mp4");
        assertThat(m.getSizeBytes()).isEqualTo(10_485_760L);
        assertThat(m.getCreatedAt()).isNotNull(); // @PrePersist stamped it
    }

    @Test
    void findByDeletedAtBeforeReturnsOnlyExpiredSoftDeleted() {
        Instant cutoff = Instant.now().minusSeconds(72L * 3600);
        persist("active", null);
        persist("fresh-deleted", Instant.now().minusSeconds(3600));
        UUID expired = persist("expired-deleted", Instant.now().minusSeconds(100L * 3600));

        List<FileMetadata> candidates = repository.findByDeletedAtBefore(cutoff);

        assertThat(candidates).extracting(FileMetadata::getFileId).containsExactly(expired);
    }

    @Test
    void hardDeleteIfSoftDeletedNeverTouchesActiveRows() {
        UUID active = persist("active", null);
        UUID deleted = persist("deleted", Instant.now());

        assertThat(repository.hardDeleteIfSoftDeleted(active)).isZero();
        assertThat(repository.hardDeleteIfSoftDeleted(deleted)).isEqualTo(1);
        em.clear();
        assertThat(repository.findById(active)).isPresent();
        assertThat(repository.findById(deleted)).isEmpty();
    }

    @Test
    void findExistingObjectKeysReturnsIntersectionIncludingSoftDeleted() {
        persist("a", null);              // object key: a-key
        persist("b", Instant.now());     // soft-deleted still counts as known

        Set<String> known = repository.findExistingObjectKeys(
                List.of("a-key", "b-key", "ghost-key"));

        assertThat(known).containsExactlyInAnyOrder("a-key", "b-key");
    }

    // name doubles as the object-key prefix so orphan assertions stay readable
    private UUID persist(String name, Instant deletedAt) {
        UUID fileId = UUID.randomUUID();
        repository.save(FileMetadata.builder()
                .fileId(fileId).objectKey(name + "-key").userId("user-a")
                .mimetype("video/mp4").sizeBytes(1L).deletedAt(deletedAt).build());
        em.flush();
        em.clear();
        return fileId;
    }

    @Test
    void activeLookupHidesSoftDeletedRows() {
        UUID fileId = UUID.randomUUID();
        repository.save(FileMetadata.builder()
                .fileId(fileId).objectKey("k").userId("user-a").mimetype("video/mp4")
                .sizeBytes(1L).deletedAt(Instant.now()).build());
        em.flush();
        em.clear();

        assertThat(repository.findByFileIdAndDeletedAtIsNull(fileId)).isEmpty();
        assertThat(repository.findById(fileId)).isPresent(); // row exists, just soft-deleted
    }
}
