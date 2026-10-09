package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import java.time.Duration;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.redis.OptionalRedisOperations;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Warm and pause disposable Redis with TCP still connected. The client comes from Boot using
 * production application.yaml budgets, not a specially shortened test connection factory.
 * Real committed PostgreSQL transactions must remain authoritative throughout the outage.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, DataRedisAutoConfiguration.class})
@Import({AnalysisService.class, AnalysisCache.class, BackpressureGuard.class, IdempotencyGuard.class,
        AnalysisStreamRegistry.class, AnalysisMetrics.class, RedisDownDegradationIntegrationTest.RedisConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RedisDownDegradationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired StringRedisTemplate template;
    @Autowired AnalysisCache cache;
    @Autowired LettuceConnectionFactory connectionFactory;
    @MockitoBean RabbitTemplate rabbitTemplate; // create() publishes the task — irrelevant here

    @Test
    void warmProductionClientHasBoundedFallbackAcceptanceAndRecoveryWhileRedisIsPaused() {
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(connectionFactory.getClientConfiguration().getClientOptions().orElseThrow()
                .getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(250));
        UUID id = createVideo();
        assertThat(service.get(id, "alice").status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(cache.getById(id)).isPresent(); // warm the actual application connection

        pause();
        try {
            long start = System.nanoTime();
            assertThat(service.get(id, "alice").status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(elapsed(start)).as("GET timeout plus PostgreSQL fallback, including skipped cache refill")
                    .isLessThan(Duration.ofMillis(1500));
            start = System.nanoTime();
            for (int i = 0; i < 3; i++) service.get(id, "alice");
            assertThat(elapsed(start)).as("shared cooldown skips repeated optional calls")
                    .isLessThan(Duration.ofMillis(500));
        } finally {
            unpause();
        }
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            service.get(id, "alice");
            assertThat(cache.getById(id)).isPresent();
        });
        template.opsForValue().get("warm-before-result");

        pause();
        try {
            long start = System.nanoTime();
            service.handleResult(result(id)); // dedup timeout, then DB acceptance + skipped eviction/mark
            assertThat(elapsed(start)).as("result transaction and after-commit callbacks")
                    .isLessThan(Duration.ofMillis(1500));
            assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
            service.handleResult(result(id)); // durable source guard still rejects duplicates without Redis
            assertThat(repository.findById(id).orElseThrow().getVideoProb()).isEqualByComparingTo("0.8");
            UUID cancelled = createVideo(); // PostgreSQL admission remains usable during the outage
            assertThat(service.cancel(cancelled, "alice").status()).isEqualTo(AnalysisStatus.CANCELLED);
        } finally {
            unpause();
        }
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            service.get(id, "alice");
            assertThat(cache.getById(id)).get().extracting(a -> a.status()).isEqualTo(AnalysisStatus.COMPLETED);
        });
        AnalysisCache restartedCache = new AnalysisCache(template, new OptionalRedisOperations(Duration.ofSeconds(2)), true);
        assertThat(restartedCache.getById(id)).as("a restarted process cannot reuse an old PENDING cache namespace").isEmpty();
        UUID healthy = createVideo();
        service.handleProgress(progress(healthy));
        assertThat(template.opsForValue().get("progress:" + healthy)).isEqualTo("50");
        service.handleResult(result(healthy));
        assertThat(template.hasKey("dedup:" + healthy + ":video")).isTrue();
        UUID cancelled = createVideo();
        service.cancel(cancelled, "alice");
        assertThat(template.opsForValue().get("cancel:" + cancelled)).isEqualTo("1");
    }

    private UUID createVideo() {
        return service.createResolved(new CreateAnalysisRequest("f", "k", AnalysisType.VIDEO, null), "alice").id();
    }

    private static Duration elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start); }
    private void pause() { redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec(); }
    private void unpause() { redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec(); }

    private Map<String, Object> progress(UUID id) {
        return Map.of("analysis_id", id.toString(), "source", "video", "progress", 50, "stage", "INFERENCE");
    }

    private Map<String, Object> result(UUID id) {
        return Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8"));
    }

    @TestConfiguration
    static class RedisConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

    }
}
