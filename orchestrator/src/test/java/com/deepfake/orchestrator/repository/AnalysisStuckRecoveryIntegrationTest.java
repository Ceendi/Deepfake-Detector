package com.deepfake.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
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

import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/**
 * Against the migrated schema: findStuckIds returns only old active rows (not fresh, not terminal),
 * and recordProgress flips PENDING->PROCESSING, heartbeats updated_at, and ignores terminal rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class AnalysisStuckRecoveryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AnalysisRepository repository;
    @Autowired TestEntityManager em;

    @Test
    void findStuckIdsReturnsOnlyOldActiveRows() {
        Instant old = Instant.now().minus(20, ChronoUnit.MINUTES);
        UUID stuckPending = persist(AnalysisStatus.PENDING, old);
        UUID stuckProcessing = persist(AnalysisStatus.PROCESSING, old);
        persist(AnalysisStatus.PROCESSING, Instant.now());   // fresh — still working
        persist(AnalysisStatus.COMPLETED, old);              // terminal — already done

        var stuck = repository.findStuckIds(Instant.now().minus(10, ChronoUnit.MINUTES));

        assertThat(stuck).containsExactlyInAnyOrder(stuckPending, stuckProcessing);
    }

    @Test
    void recordProgressFlipsPendingAndHeartbeats() {
        UUID id = persist(AnalysisStatus.PENDING, Instant.now().minus(5, ChronoUnit.MINUTES));
        Instant firstTouch = Instant.now();

        int flipped = repository.recordProgress(id, AnalysisStatus.PROCESSING, ACTIVE, firstTouch);
        em.flush();
        em.clear();

        assertThat(flipped).isEqualTo(1);
        Analysis afterFlip = em.find(Analysis.class, id);
        assertThat(afterFlip.getStatus()).isEqualTo(AnalysisStatus.PROCESSING);

        int beat = repository.recordProgress(id, AnalysisStatus.PROCESSING, ACTIVE,
                firstTouch.plusSeconds(30));
        em.flush();
        em.clear();

        assertThat(beat).isEqualTo(1);
        assertThat(em.find(Analysis.class, id).getUpdatedAt()).isAfter(afterFlip.getUpdatedAt());
    }

    @Test
    void recordProgressIgnoresTerminal() {
        UUID id = persist(AnalysisStatus.CANCELLED, Instant.now());

        int rows = repository.recordProgress(id, AnalysisStatus.PROCESSING, ACTIVE, Instant.now());

        assertThat(rows).isZero();
        assertThat(em.find(Analysis.class, id).getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
    }

    private static final EnumSet<AnalysisStatus> ACTIVE =
            EnumSet.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING);

    private UUID persist(AnalysisStatus status, Instant updatedAt) {
        Analysis saved = em.persistFlushFind(Analysis.builder()
                .userId("alice").fileId("f").fileKey("k").type(AnalysisType.VIDEO).build());
        // @PrePersist forces PENDING + now(); override status/updated_at so the scan window is testable.
        em.getEntityManager()
                .createNativeQuery("UPDATE analysis SET status = ?1, updated_at = ?2 WHERE id = ?3")
                .setParameter(1, status.name())
                .setParameter(2, updatedAt.atOffset(java.time.ZoneOffset.UTC))
                .setParameter(3, saved.getId())
                .executeUpdate();
        em.clear();
        return saved.getId();
    }
}
