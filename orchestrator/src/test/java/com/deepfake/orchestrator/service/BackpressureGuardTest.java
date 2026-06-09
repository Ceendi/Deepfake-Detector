package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.deepfake.orchestrator.exception.TooManyAnalysesException;

class BackpressureGuardTest {

    private static final String KEY = "analyses:inflight";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private BackpressureGuard guard;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        guard = new BackpressureGuard(redis, 2, 5); // maxInflight=2, retryAfter=5
    }

    @Test
    void acquireUnderLimitDoesNotRollBack() {
        when(ops.increment(KEY)).thenReturn(2L); // == limit, still allowed

        assertThatCode(guard::acquire).doesNotThrowAnyException();
        verify(ops, never()).decrement(KEY);
    }

    @Test
    void acquireOverLimitThrows429AndRollsBack() {
        when(ops.increment(KEY)).thenReturn(3L); // > limit

        assertThatThrownBy(guard::acquire)
                .isInstanceOf(TooManyAnalysesException.class)
                .hasFieldOrPropertyWithValue("queuePosition", 3)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 5);
        verify(ops).decrement(KEY); // reservation undone
    }

    @Test
    void acquireFailsOpenWhenRedisDown() {
        when(ops.increment(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(guard::acquire).doesNotThrowAnyException(); // admit rather than 500
    }

    @Test
    void acquireOverLimitStill429WhenRollbackFails() {
        when(ops.increment(KEY)).thenReturn(3L); // > limit
        when(ops.decrement(KEY)).thenThrow(new RedisConnectionFailureException("down mid-rollback"));

        assertThatThrownBy(guard::acquire) // 429, not 500 — fail-open on the rollback
                .isInstanceOf(TooManyAnalysesException.class);
    }

    @Test
    void releaseDecrements() {
        when(ops.decrement(KEY)).thenReturn(1L);

        guard.release();
        verify(ops).decrement(KEY);
    }

    @Test
    void releaseFloorsNegativeCounterAtZero() {
        when(ops.decrement(KEY)).thenReturn(-1L);

        guard.release();
        verify(ops).set(KEY, "0");
    }
}
