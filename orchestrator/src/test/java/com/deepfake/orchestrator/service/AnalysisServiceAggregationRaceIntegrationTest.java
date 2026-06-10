package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
 * The race the week-6 fix targets, on real Postgres (H2 can't reproduce the row-lock): video and audio
 * results for one FULL analysis land concurrently. The disjoint-column writes must both survive and the
 * analysis must complete exactly once — proving no last-write-wins and a single slot release.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(AnalysisService.class)
@Testcontainers
class AnalysisServiceAggregationRaceIntegrationTest {

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

    private TransactionTemplate requiresNew; // commits independently of the test-method transaction

    @BeforeEach
    void setUp() {
        requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // idempotency mock returns false by default -> no dedup interference with the race
    }

    @Test
    void concurrentVideoAndAudioBothSurviveAndCompleteOnce() throws Exception {
        int rounds = 20;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                UUID id = newAnalysis(AnalysisType.FULL);
                CountDownLatch start = new CountDownLatch(1);

                Future<?> video = pool.submit(() -> {
                    start.await();
                    service.handleResult(result(id, "video", "0.8"));
                    return null;
                });
                Future<?> audio = pool.submit(() -> {
                    start.await();
                    service.handleResult(result(id, "audio", "0.4"));
                    return null;
                });
                start.countDown();
                video.get();
                audio.get();

                Analysis a = read(id);
                assertThat(a.getVideoProb()).as("video prob survived round %d", i).isNotNull();
                assertThat(a.getAudioProb()).as("audio prob survived round %d", i).isNotNull();
                assertThat(a.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(backpressure, times(rounds)).release(); // exactly one release per analysis, never two
    }

    private UUID newAnalysis(AnalysisType type) {
        return requiresNew.execute(s -> repository.save(Analysis.builder()
                .userId("alice").fileId("f").fileKey("k").type(type).build()).getId());
    }

    private Analysis read(UUID id) {
        return requiresNew.execute(s -> repository.findById(id).orElseThrow());
    }

    private Map<String, Object> result(UUID id, String source, String prob) {
        return Map.of("analysis_id", id.toString(), "source", source, "status", "COMPLETED",
                "result", Map.of("prob_fake", prob));
    }
}
