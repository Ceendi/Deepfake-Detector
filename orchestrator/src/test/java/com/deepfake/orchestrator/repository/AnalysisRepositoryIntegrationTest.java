package com.deepfake.orchestrator.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
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
