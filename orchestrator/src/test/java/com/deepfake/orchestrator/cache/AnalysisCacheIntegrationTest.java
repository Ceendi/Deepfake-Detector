package com.deepfake.orchestrator.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/**
 * Round-trips an AnalysisResponse through real Redis (Testcontainers): the cached value survives
 * serialization (Instant, BigDecimal, enums) and absent/evicted keys read back as empty.
 */
@Testcontainers
class AnalysisCacheIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);

    AnalysisCache cache;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        cf.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(cf);
        template.afterPropertiesSet();
        cache = new AnalysisCache(template);
    }

    @Test
    void putThenGetRoundTripsAllFields() {
        AnalysisResponse stored = sample(UUID.randomUUID());
        cache.putById(stored);

        Optional<AnalysisResponse> loaded = cache.getById(stored.id());

        assertThat(loaded).isPresent();
        AnalysisResponse got = loaded.get();
        assertThat(got.id()).isEqualTo(stored.id());
        assertThat(got.userId()).isEqualTo("user-a");
        assertThat(got.type()).isEqualTo(AnalysisType.VIDEO);
        assertThat(got.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(got.verdict()).isEqualTo("FAKE");
        assertThat(got.confidence()).isEqualByComparingTo("0.7400");
        assertThat(got.createdAt()).isEqualTo(stored.createdAt());
    }

    @Test
    void getReturnsEmptyForAbsentKey() {
        assertThat(cache.getById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void evictRemovesEntry() {
        AnalysisResponse stored = sample(UUID.randomUUID());
        cache.putById(stored);

        cache.evictById(stored.id());

        assertThat(cache.getById(stored.id())).isEmpty();
    }

    private static AnalysisResponse sample(UUID id) {
        return new AnalysisResponse(id, "user-a", "file-1", "key-1", AnalysisType.VIDEO,
                AnalysisStatus.COMPLETED, "FAKE", new BigDecimal("0.7400"), new BigDecimal("0.8700"),
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:02Z"));
    }
}
