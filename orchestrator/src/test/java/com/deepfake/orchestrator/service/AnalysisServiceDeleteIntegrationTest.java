package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.response.UserStats;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * delete() against real Postgres + the migrated schema: a hard delete removes the row from every read
 * path with no soft-delete predicate, and {@code userStats} (the homepage aggregate the user worried
 * about) recomputes without it. Sibling analyses — including the still-active one and another user's —
 * are untouched. This is the regression proof that deleting an analysis does not skew the stats.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(AnalysisService.class)
@Testcontainers
class AnalysisServiceDeleteIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    AnalysisService service;
    @Autowired
    AnalysisRepository repository;
    @Autowired
    TestEntityManager em;

    @MockitoBean
    BackpressureGuard backpressure;
    @MockitoBean
    IdempotencyGuard idempotency;
    @MockitoBean
    AnalysisCache cache;
    @MockitoBean
    AnalysisStreamRegistry streams;
    @MockitoBean
    RabbitTemplate rabbitTemplate;
    @MockitoBean
    StringRedisTemplate redis;
    @MockitoBean
    AnalysisMetrics metrics;

    @Test
    void deleteRemovesRowAndStatsExcludeIt() {
        Instant now = Instant.now();
        Instant since = now.minus(7, ChronoUnit.DAYS);
        UUID completed = persist("alice", AnalysisStatus.COMPLETED, "FAKE", "0.8000", now.minusSeconds(60));
        // Record a Grad-CAM object on the completed row (real jsonb) so we can assert its key round-trips
        // back through delete() for the caller to reclaim from storage.
        em.getEntityManager()
                .createNativeQuery("UPDATE analysis SET video_details = CAST(?1 AS jsonb) WHERE id = ?2")
                .setParameter(1, "{\"gradcamKeys\": [\"" + completed + "/video/cam.png\"]}")
                .setParameter(2, completed)
                .executeUpdate();
        em.clear();
        UUID failed = persist("alice", AnalysisStatus.FAILED, null, null, now.minusSeconds(120));
        UUID processing = persist("alice", AnalysisStatus.PROCESSING, null, null, now.minusSeconds(180));
        UUID bob = persist("bob", AnalysisStatus.COMPLETED, "REAL", "0.6000", now.minusSeconds(60));

        UserStats before = repository.userStats("alice", since);
        assertThat(before.total()).isEqualTo(3);
        assertThat(before.completed()).isEqualTo(1);

        List<String> reclaimable = service.delete(completed, "alice");
        em.flush(); // force the DELETE to the DB, then read fresh
        em.clear();

        // The Grad-CAM key round-trips out of the real jsonb column for the caller to reclaim.
        assertThat(reclaimable).containsExactly(completed + "/video/cam.png");
        assertThat(repository.findById(completed)).isEmpty();
        // Siblings untouched: the active analysis and the other user's row are left alone.
        assertThat(repository.findById(failed)).isPresent();
        assertThat(repository.findById(processing)).isPresent();
        assertThat(repository.findById(bob)).isPresent();

        UserStats after = repository.userStats("alice", since);
        assertThat(after.total()).isEqualTo(2);
        assertThat(after.completed()).isZero();
        assertThat(after.failed()).isEqualTo(1);
        assertThat(after.inProgress()).isEqualTo(1);
        assertThat(after.avgConfidence()).isNull(); // the only COMPLETED (the one with a confidence) is gone

        verify(metrics).deleted(AnalysisStatus.COMPLETED);
    }

    // persist() forces PENDING/@PrePersist timestamps; overwrite the stat-relevant columns natively
    // (same approach as AnalysisRepositoryIntegrationTest).
    private UUID persist(String userId, AnalysisStatus status, String verdict, String confidence, Instant createdAt) {
        Analysis saved = em.persistFlushFind(Analysis.builder()
                .userId(userId).fileId("f").fileKey("k").type(AnalysisType.VIDEO).build());
        em.getEntityManager()
                .createNativeQuery("UPDATE analysis SET status = ?1, verdict = ?2, confidence = ?3, "
                        + "created_at = ?4 WHERE id = ?5")
                .setParameter(1, status.name())
                .setParameter(2, verdict)
                .setParameter(3, confidence == null ? null : new BigDecimal(confidence))
                .setParameter(4, createdAt.atOffset(ZoneOffset.UTC))
                .setParameter(5, saved.getId())
                .executeUpdate();
        em.clear();
        return saved.getId();
    }
}
