package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

/**
 * Best-effort result dedup over Redis (D6). A cheap early-exit for at-least-once redeliveries, not
 * the correctness authority — that is the conditional per-source DB update. Keyed per-source because a FULL
 * analysis produces two results (video + audio) under one analysis_id. Fail-open: if Redis is down
 * the check misses and the mark is dropped, so processing falls back to the DB guard.
 */
@Component
@Import(OptionalRedisOperations.class)
public class IdempotencyGuard {

    private final StringRedisTemplate redis;
    private final OptionalRedisOperations optionalRedis;
    private final Duration ttl;

    public IdempotencyGuard(StringRedisTemplate redis, OptionalRedisOperations optionalRedis,
            @Value("${reliability.idempotency.ttl-seconds:900}") long ttlSeconds) {
        this.redis = redis;
        this.optionalRedis = optionalRedis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    private String key(UUID analysisId, String source) {
        return "dedup:" + analysisId + ":" + source;
    }

    public boolean alreadyProcessed(UUID analysisId, String source) {
        return optionalRedis.get("dedup check",
                () -> Boolean.TRUE.equals(redis.hasKey(key(analysisId, source))), false);
    }

    // Set only after the DB transaction commits (caller registers this on afterCommit), so the key
    // is only a hint for an accepted result; expiry or loss cannot permit a second DB write.
    public void markProcessed(UUID analysisId, String source) {
        optionalRedis.run("dedup mark", () -> redis.opsForValue().set(key(analysisId, source), "1", ttl));
    }

    // Drop both per-source dedup keys when the analysis is deleted, so its Redis footprint doesn't
    // linger until TTL. Fail-open like the rest of the guard — keys self-expire anyway.
    public void clear(UUID analysisId) {
        optionalRedis.run("dedup cleanup", () -> redis.delete(
                List.of(key(analysisId, "video"), key(analysisId, "audio"))));
    }
}
