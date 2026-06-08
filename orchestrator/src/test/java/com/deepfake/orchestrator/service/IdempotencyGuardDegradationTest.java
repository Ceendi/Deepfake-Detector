package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Fail-open (D6): Redis down -> the check misses (process anyway) and the mark is swallowed. */
@ExtendWith(MockitoExtension.class)
class IdempotencyGuardDegradationTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    @Test
    void checkMissesWhenRedisDown() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        IdempotencyGuard guard = new IdempotencyGuard(redis, 900);

        assertThat(guard.alreadyProcessed(UUID.randomUUID(), "video")).isFalse();
    }

    @Test
    void markSwallowsRedisFailure() {
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        IdempotencyGuard guard = new IdempotencyGuard(redis, 900);

        assertThatCode(() -> guard.markProcessed(UUID.randomUUID(), "video")).doesNotThrowAnyException();
    }
}
