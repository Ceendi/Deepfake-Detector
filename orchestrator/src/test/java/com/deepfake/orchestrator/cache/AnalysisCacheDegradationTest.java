package com.deepfake.orchestrator.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;

/**
 * Graceful degradation (D6): when Redis is down every cache op fails soft — reads miss (so the
 * service falls back to the DB) and writes/evicts are swallowed, never propagating a 500.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisCacheDegradationTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    AnalysisCache cache;

    @BeforeEach
    void setUp() {
        cache = new AnalysisCache(redis);
    }

    @Test
    void getByIdReturnsEmptyWhenRedisDown() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.getById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void putByIdSwallowsRedisFailure() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.putById(sample())).doesNotThrowAnyException();
    }

    @Test
    void evictByIdSwallowsRedisFailure() {
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> cache.evictById(UUID.randomUUID())).doesNotThrowAnyException();
    }

    private static AnalysisResponse sample() {
        return new AnalysisResponse(UUID.randomUUID(), "user-a", "file-1", "key-1",
                AnalysisType.VIDEO, AnalysisStatus.PENDING, null, null, null, null, null, null,
                Instant.now(), Instant.now());
    }
}
