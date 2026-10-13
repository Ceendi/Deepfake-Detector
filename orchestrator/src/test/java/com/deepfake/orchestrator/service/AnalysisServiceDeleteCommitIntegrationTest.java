package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Committed delete/terminal side effects against real PostgreSQL and Redis: deleting a FAILED or
 * CANCELLED analysis keeps the detector stop flag, a concurrent duplicate delete is a clean 404
 * (cleanup work and metric once), and a rolled-back transaction counts nothing.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, AnalysisServiceDeleteCommitIntegrationTest.RedisConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AnalysisServiceDeleteCommitIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @MockitoBean BackpressureGuard backpressure;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean AnalysisMetrics metrics;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactions);
        jdbc.update("DELETE FROM artifact_cleanup");
        jdbc.update("DELETE FROM analysis");
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void cancelThenDeleteKeepsTheStopFlagAndDropsProgress() {
        UUID id = persist(AnalysisType.FULL);
        service.cancel(id, "alice");
        redis.opsForValue().set("progress:" + id, "40");
        assertThat(redis.hasKey("cancel:" + id)).isTrue();

        service.delete(id, "alice");

        // Queued/in-flight detector work must still see the stop signal after the row is gone.
        assertThat(redis.opsForValue().get("cancel:" + id)).isEqualTo("1");
        assertThat(redis.getExpire("cancel:" + id)).isBetween(7100L, 7200L);
        assertThat(redis.hasKey("progress:" + id)).isFalse();
        assertThat(repository.existsById(id)).isFalse();
    }

    @Test
    void deleteRefreshesAMissingFlagForFailedButNeverFlagsCompleted() {
        UUID failed = persist(AnalysisType.VIDEO);
        jdbc.update("UPDATE analysis SET status = 'FAILED' WHERE id = ?", failed);
        UUID completed = persist(AnalysisType.VIDEO);
        jdbc.update("UPDATE analysis SET status = 'COMPLETED', verdict = 'REAL', confidence = 0.2 WHERE id = ?", completed);

        service.delete(failed, "alice");
        service.delete(completed, "alice");

        assertThat(redis.opsForValue().get("cancel:" + failed)).isEqualTo("1");
        assertThat(redis.hasKey("cancel:" + completed)).isFalse();
    }

    @Test
    void concurrentDuplicateDeleteIsOne204AndOne404WithSideEffectsOnce() throws Exception {
        int rounds = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                UUID id = persist(AnalysisType.VIDEO);
                String key = id + "/video/gradcam_frame_00_" + "a".repeat(32) + ".png";
                jdbc.update("UPDATE analysis SET status = 'CANCELLED', video_details = CAST(? AS jsonb) WHERE id = ?",
                        "{\"gradcamKeys\": [\"" + key + "\"]}", id);
                // Both requests read the terminal row before either deletes, as two overlapping HTTP calls do.
                CyclicBarrier bothRead = new CyclicBarrier(2);
                List<Future<HttpStatus>> outcomes = new ArrayList<>();
                for (int request = 0; request < 2; request++) {
                    outcomes.add(pool.submit(() -> deleteAfterRead(id, bothRead)));
                }
                List<HttpStatus> statuses = new ArrayList<>();
                for (Future<HttpStatus> outcome : outcomes) statuses.add(outcome.get(30, TimeUnit.SECONDS));

                assertThat(statuses).as("round %d", round)
                        .containsExactlyInAnyOrder(HttpStatus.NO_CONTENT, HttpStatus.NOT_FOUND);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_cleanup WHERE object_key = ?",
                        Long.class, key)).isOne();
                assertThat(repository.existsById(id)).isFalse();
            }
        } finally {
            pool.shutdownNow();
        }
        verify(metrics, times(rounds)).deleted(AnalysisStatus.CANCELLED);
    }

    @Test
    void rolledBackDeleteAndTerminalTransitionsCountNothing() {
        UUID deleted = persist(AnalysisType.VIDEO);
        jdbc.update("UPDATE analysis SET status = 'CANCELLED' WHERE id = ?", deleted);
        UUID cancelled = persist(AnalysisType.VIDEO);
        UUID dlq = persist(AnalysisType.VIDEO);
        UUID stuck = persist(AnalysisType.VIDEO);
        jdbc.update("UPDATE analysis SET created_at = CURRENT_TIMESTAMP - INTERVAL '7201 seconds' WHERE id = ?", stuck);
        List<Consumer<Void>> steps = List.of(
                v -> service.delete(deleted, "alice"),
                v -> service.cancel(cancelled, "alice"),
                v -> service.failFromDlq(dlq, "video", "delivery_limit"),
                v -> service.failStuck(stuck, 600));

        for (Consumer<Void> step : steps) {
            tx.executeWithoutResult(status -> {
                step.accept(null);
                status.setRollbackOnly();
            });
        }

        assertThat(repository.existsById(deleted)).isTrue();
        assertThat(statusOf(cancelled)).isEqualTo("PENDING");
        assertThat(statusOf(dlq)).isEqualTo("PENDING");
        assertThat(statusOf(stuck)).isEqualTo("PENDING");
        verify(metrics, never()).deleted(any());
        verify(metrics, never()).terminal(any(), any());
        verify(metrics, never()).dlqFailure();
        verify(metrics, never()).stuckRecovery();

        for (Consumer<Void> step : steps) {
            tx.executeWithoutResult(status -> step.accept(null));
        }

        verify(metrics).deleted(AnalysisStatus.CANCELLED);
        verify(metrics).terminal(AnalysisStatus.CANCELLED, AnalysisType.VIDEO);
        verify(metrics, times(2)).terminal(AnalysisStatus.FAILED, AnalysisType.VIDEO);
        verify(metrics).dlqFailure();
        verify(metrics).stuckRecovery();
    }

    private HttpStatus deleteAfterRead(UUID id, CyclicBarrier bothRead) {
        try {
            tx.executeWithoutResult(status -> {
                repository.findById(id).orElseThrow();
                await(bothRead);
                service.delete(id, "alice");
            });
            return HttpStatus.NO_CONTENT;
        } catch (ResponseStatusException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID persist(AnalysisType type) {
        return tx.execute(s -> repository.save(Analysis.builder()
                .userId("alice").fileId("f").fileKey("k").type(type).build()).getId());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM analysis WHERE id = ?", String.class, id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisConfig {
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        }

        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }
}
