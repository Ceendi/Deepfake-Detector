package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import java.time.Duration;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * With a real Postgres + Redis, killing Redis mid-flight must not stop processing (D6): every Redis
 * touchpoint fails open, so an analysis still reaches COMPLETED in the DB and GET falls back to it.
 * Uses real transactions (the service beans) over the migrated schema; RabbitMQ publish is mocked.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, AnalysisCache.class, BackpressureGuard.class, IdempotencyGuard.class,
        AnalysisStreamRegistry.class, AnalysisMetrics.class, RedisDownDegradationIntegrationTest.RedisConfig.class})
@Testcontainers
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
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @MockitoBean RabbitTemplate rabbitTemplate; // create() publishes the task — irrelevant here

    @Test
    void analysisStillCompletesAfterRedisDies() {
        UUID id = service.create(new CreateAnalysisRequest("f", "k", AnalysisType.VIDEO), "alice").id();

        redis.stop();

        assertThatCode(() -> service.handleProgress(progress(id))).doesNotThrowAnyException();
        service.handleResult(result(id));

        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(service.get(id, "alice").status()).isEqualTo(AnalysisStatus.COMPLETED); // DB fallback
    }

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

        @Bean
        StringRedisTemplate redisTemplate() {
            // Short command timeout so ops against the stopped Redis fail fast (fail-open) instead of
            // hanging on Lettuce's 60s default.
            LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofMillis(500))
                    .shutdownTimeout(Duration.ZERO)
                    .build();
            LettuceConnectionFactory cf = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                    clientConfig);
            cf.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(cf);
            template.afterPropertiesSet();
            return template;
        }
    }
}
