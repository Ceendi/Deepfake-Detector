package com.deepfake.orchestrator.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.deepfake.orchestrator.exception.TooManyAnalysesException;

import lombok.extern.slf4j.Slf4j;

/**
 * In-flight analysis limiter backed by a single Redis counter. acquire() reserves a slot on POST,
 * release() frees it at a terminal state. Counts analyses, not detector tasks (one FULL analysis =
 * two tasks = one slot). Fail-open: if Redis is down we admit the request — backpressure is overload
 * protection, not a security gate (D6 graceful degradation).
 */
@Slf4j
@Component
public class BackpressureGuard {

    private static final String KEY = "analyses:inflight";

    private final StringRedisTemplate redis;
    private final int maxInflight;
    private final int retryAfterSeconds;

    public BackpressureGuard(StringRedisTemplate redis,
            @Value("${backpressure.max-inflight:20}") int maxInflight,
            @Value("${backpressure.retry-after-seconds:5}") int retryAfterSeconds) {
        this.redis = redis;
        this.maxInflight = maxInflight;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Reserve a slot. INCR-then-check-then-rollback avoids TOCTOU. Throws 429 when over the limit. */
    public void acquire() {
        long inflight;
        try {
            Long incremented = redis.opsForValue().increment(KEY);
            inflight = incremented != null ? incremented : 0L;
        } catch (DataAccessException e) {
            log.warn("backpressure degraded (Redis down) — failing open: {}", e.getMessage());
            return;
        }
        if (inflight > maxInflight) {
            try {
                redis.opsForValue().decrement(KEY); // undo our reservation
            } catch (DataAccessException e) {
                // Redis died after our increment: still answer 429 (the intent), accept a tiny +1 drift.
                log.warn("backpressure rollback skipped (degraded): {}", e.getMessage());
            }
            throw new TooManyAnalysesException((int) inflight, retryAfterSeconds);
        }
    }

    /** Free a slot at a terminal transition. Floor at 0 to bound drift from a crash mid-flight. */
    public void release() {
        try {
            Long inflight = redis.opsForValue().decrement(KEY);
            if (inflight != null && inflight < 0) {
                redis.opsForValue().set(KEY, "0");
            }
        } catch (DataAccessException e) {
            log.warn("backpressure release skipped (degraded): {}", e.getMessage());
        }
    }
}
