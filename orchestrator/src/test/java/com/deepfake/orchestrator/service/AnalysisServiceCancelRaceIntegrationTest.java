package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * cancel() (HTTP thread) racing a completing detector result (AMQP thread) on real Postgres — a race
 * that exists today, not just under future concurrency. The status CAS must pick exactly one winner:
 * the analysis ends terminal once, the slot releases once, and the losing cancel reports 409 truthfully.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(AnalysisService.class)
@Testcontainers
class AnalysisServiceCancelRaceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired PlatformTransactionManager txManager;

    @MockitoBean BackpressureGuard backpressure;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean RabbitTemplate rabbitTemplate;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AnalysisMetrics metrics;

    private TransactionTemplate requiresNew;

    @BeforeEach
    void setUp() {
        requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A winning cancel sets the cancel:{id} flag after commit; the bare mock would return a
        // null ValueOperations and NPE inside the afterCommit synchronization.
        lenient().when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
    }

    @Test
    void cancelAndCompletingResultResolveToExactlyOneWinner() throws Exception {
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                UUID id = newAnalysis();
                CountDownLatch start = new CountDownLatch(1);

                Future<HttpStatus> cancel = pool.submit(() -> {
                    start.await();
                    try {
                        service.cancel(id, "alice");
                        return HttpStatus.OK; // won the race -> CANCELLED
                    } catch (ResponseStatusException e) {
                        return HttpStatus.valueOf(e.getStatusCode().value()); // lost -> 409
                    }
                });
                Future<?> result = pool.submit(() -> {
                    start.await();
                    service.handleResult(completedVideo(id));
                    return null;
                });
                start.countDown();
                HttpStatus cancelOutcome = cancel.get();
                result.get();

                AnalysisStatus status = read(id).getStatus();
                assertThat(status).isIn(AnalysisStatus.CANCELLED, AnalysisStatus.COMPLETED);
                if (status == AnalysisStatus.COMPLETED) {
                    assertThat(cancelOutcome).as("cancel lost the race round %d", i).isEqualTo(HttpStatus.CONFLICT);
                } else {
                    assertThat(cancelOutcome).as("cancel won the race round %d", i).isEqualTo(HttpStatus.OK);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        verify(backpressure, times(rounds)).release(); // one winner per round -> one release, never two
    }

    private UUID newAnalysis() {
        return requiresNew.execute(s -> repository.save(Analysis.builder()
                .userId("alice").fileId("f").fileKey("k").type(AnalysisType.VIDEO).build()).getId());
    }

    private Analysis read(UUID id) {
        return requiresNew.execute(s -> repository.findById(id).orElseThrow());
    }

    private Map<String, Object> completedVideo(UUID id) {
        return Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8"));
    }
}
