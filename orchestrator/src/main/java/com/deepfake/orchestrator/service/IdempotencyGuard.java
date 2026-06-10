package com.deepfake.orchestrator.service;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort result dedup over Redis (D6). A cheap early-exit for at-least-once redeliveries, not
 * the correctness authority — that is the DB isTerminal() guard. Keyed per-source because a FULL
 * analysis produces two results (video + audio) under one analysis_id. Fail-open: if Redis is down
 * the check misses and the mark is dropped, so processing falls back to the DB guard.
 */
@Slf4j
@Component
public class IdempotencyGuard {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyGuard(StringRedisTemplate redis,
            @Value("${reliability.idempotency.ttl-seconds:900}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    private String key(UUID analysisId, String source) {
        return "dedup:" + analysisId + ":" + source;
    }

    public boolean alreadyProcessed(UUID analysisId, String source) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(analysisId, source)));
        } catch (DataAccessException e) {
            log.warn("idempotency check degraded (Redis down) — failing open: {}", e.getMessage());
            return false;
        }
    }

    // Set only after the DB transaction commits (caller registers this on afterCommit), so the key
    // exists iff the result was persisted — a retried-after-rollback result is never skipped as a dup.
    public void markProcessed(UUID analysisId, String source) {
        try {
            redis.opsForValue().set(key(analysisId, source), "1", ttl);
        } catch (DataAccessException e) {
            log.warn("idempotency mark skipped (Redis down): {}", e.getMessage());
        }
    }
}
