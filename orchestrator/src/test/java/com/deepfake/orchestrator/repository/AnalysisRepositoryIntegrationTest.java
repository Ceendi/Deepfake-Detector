package com.deepfake.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.dto.response.UserStats;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/**
 * Verifies the paginated interface projection against the real migrated schema (Testcontainers +
 * Flyway, ddl-auto: none): query-level scoping to one user (the list-side IDOR guard), page size,
 * createdAt DESC ordering, and that enum columns project correctly into the closed projection.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Testcontainers
class AnalysisRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    AnalysisRepository repository;
    @Autowired
    TestEntityManager em;

    @Test
    void findByUserIdReturnsScopedPaginatedProjectionNewestFirst() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        UUID newest = persist("alice", base.plusSeconds(20));
        UUID middle = persist("alice", base.plusSeconds(10));
        persist("alice", base);
        persist("bob", base.plusSeconds(30)); // foreign user — must never leak into the result

        Pageable firstPageOfTwo = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AnalysisSummary> page = repository.findByUserId("alice", firstPageOfTwo);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(AnalysisSummary::getId)
                .containsExactly(newest, middle);

        AnalysisSummary head = page.getContent().getFirst();
        assertThat(head.getType()).isEqualTo(AnalysisType.VIDEO);
        assertThat(head.getStatus()).isEqualTo(AnalysisStatus.PENDING);
    }

    // The details Map must bind as jsonb inside the bulk JPQL UPDATE (not just on entity persist) —
    // that's the write path handleResult actually uses.
    @Test
    void writeAudioProbPersistsDetailsJsonb() {
        UUID id = persist("alice", Instant.now());
        Map<String, Object> details = Map.of(
                "modelVersion", "v1.2.0-fast",
                "gradcamKeys", List.of(id + "/audio/gradcam.png"),
                "metadata", Map.of("duration_seconds", 12.5));

        int rows = repository.writeAudioProb(id, new BigDecimal("0.9100"), details,
                List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING), Instant.now());

        assertThat(rows).isEqualTo(1);
        Analysis fresh = repository.findById(id).orElseThrow();
        assertThat(fresh.getAudioDetails())
                .containsEntry("modelVersion", "v1.2.0-fast")
                .containsEntry("gradcamKeys", List.of(id + "/audio/gradcam.png"));
        assertThat(fresh.getVideoDetails()).isNull(); // disjoint columns stay untouched
    }

    @Test
    void writeVideoProbAcceptsNullDetails() {
        UUID id = persist("alice", Instant.now());

        int rows = repository.writeVideoProb(id, new BigDecimal("0.4200"), null,
                List.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING), Instant.now());

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getVideoDetails()).isNull();
    }

    @Test
    void userStatsAggregatesAndScopesToOneUser() {
        Instant now = Instant.now();
        Instant old = now.minusSeconds(30L * 24 * 3600); // outside the 7-day window
        statRow("alice", now.minusSeconds(60), AnalysisType.VIDEO, AnalysisStatus.COMPLETED, "FAKE", "0.8000");
        statRow("alice", old, AnalysisType.FULL, AnalysisStatus.COMPLETED, "REAL", "0.6000");
        statRow("alice", now.minusSeconds(120), AnalysisType.AUDIO, AnalysisStatus.FAILED, null, null);
        statRow("alice", now.minusSeconds(180), AnalysisType.VIDEO, AnalysisStatus.PROCESSING, null, null);
        statRow("alice", old, AnalysisType.AUDIO, AnalysisStatus.CANCELLED, null, null);
        statRow("bob", now, AnalysisType.VIDEO, AnalysisStatus.COMPLETED, "FAKE", "0.9000"); // must not leak

        UserStats stats = repository.userStats("alice", now.minusSeconds(7L * 24 * 3600));

        assertThat(stats.total()).isEqualTo(5);
        assertThat(stats.completed()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.cancelled()).isEqualTo(1);
        assertThat(stats.inProgress()).isEqualTo(1);
        assertThat(stats.video()).isEqualTo(2);
        assertThat(stats.audio()).isEqualTo(2);
        assertThat(stats.full()).isEqualTo(1);
        assertThat(stats.fake()).isEqualTo(1);
        assertThat(stats.real()).isEqualTo(1);
        assertThat(stats.avgConfidence()).isEqualTo(0.7, offset(1e-9));
        assertThat(stats.last7Days()).isEqualTo(3);
        // timestamptz keeps microseconds; Instant.now() may carry nanos — compare with tolerance
        assertThat(stats.lastAnalysisAt()).isCloseTo(now.minusSeconds(60), within(1, ChronoUnit.MILLIS));
    }

    @Test
    void userStatsWithNoAnalysesReturnsZerosNotNulls() {
        UserStats stats = repository.userStats("nobody", Instant.now().minusSeconds(7L * 24 * 3600));

        assertThat(stats.total()).isZero();
        assertThat(stats.completed()).isZero();
        assertThat(stats.inProgress()).isZero();
        assertThat(stats.fake()).isZero();
        assertThat(stats.last7Days()).isZero();
        assertThat(stats.avgConfidence()).isNull();
        assertThat(stats.lastAnalysisAt()).isNull();
    }

    // persist() forces PENDING/@PrePersist timestamps; overwrite the stat-relevant columns natively.
    private UUID statRow(String userId, Instant createdAt, AnalysisType type, AnalysisStatus status,
            String verdict, String confidence) {
        UUID id = persist(userId, createdAt);
        em.getEntityManager()
                .createNativeQuery("UPDATE analysis SET type = ?1, status = ?2, verdict = ?3, "
                        + "confidence = ?4 WHERE id = ?5")
                .setParameter(1, type.name())
                .setParameter(2, status.name())
                .setParameter(3, verdict)
                .setParameter(4, confidence == null ? null : new BigDecimal(confidence))
                .setParameter(5, id)
                .executeUpdate();
        em.clear();
        return id;
    }

    private UUID persist(String userId, Instant createdAt) {
        Analysis saved = em.persistFlushFind(Analysis.builder()
                .userId(userId).fileId("f").fileKey("k").type(AnalysisType.VIDEO).build());
        // @PrePersist stamps createdAt = now(); override it so DESC ordering is deterministic.
        em.getEntityManager()
                .createNativeQuery("UPDATE analysis SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt.atOffset(ZoneOffset.UTC))
                .setParameter(2, saved.getId())
                .executeUpdate();
        em.clear();
        return saved.getId();
    }
}
