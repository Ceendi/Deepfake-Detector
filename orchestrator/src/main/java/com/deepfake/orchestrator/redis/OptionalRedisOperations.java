package com.deepfake.orchestrator.redis;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Shared, brief bypass for optional hints. PostgreSQL remains the admission/result authority.
 * Cache namespaces are unique per process and failure, so skipped evictions cannot survive recovery
 * or restart. Abandoned entries retain their existing 60-second TTL.
 */
@Slf4j
@Component
public class OptionalRedisOperations {
    private final long cooldownNanos;
    private final LongSupplier clock;
    private final AtomicLong failedAt = new AtomicLong();
    private volatile boolean degraded;
    private volatile String cacheNamespace = ":" + UUID.randomUUID();

    @Autowired
    public OptionalRedisOperations(@Value("${reliability.redis.degraded-bypass:2s}") Duration cooldown) {
        this(cooldown, System::nanoTime);
    }

    OptionalRedisOperations(Duration cooldown, LongSupplier clock) {
        if (cooldown.isNegative() || cooldown.isZero()) {
            throw new IllegalArgumentException("Redis degraded bypass must be positive");
        }
        this.cooldownNanos = cooldown.toNanos();
        this.clock = clock;
    }

    public <T> T get(String operation, Supplier<T> action, T fallback) {
        if (degraded && clock.getAsLong() - failedAt.get() < cooldownNanos) return fallback;
        try {
            return action.get();
        } catch (DataAccessException ex) {
            // Failed/skipped evictions must not expose pre-outage snapshots after recovery.
            // Only cache keys change namespace; dedup, progress and cancellation contracts do not.
            cacheNamespace = ":" + UUID.randomUUID();
            failedAt.set(clock.getAsLong());
            degraded = true;
            log.warn("Optional Redis {} failed; bypassing hints briefly: {}", operation, ex.getMessage());
            return fallback;
        }
    }

    public String cacheNamespace() { return cacheNamespace; }

    public void run(String operation, Runnable action) {
        get(operation, () -> { action.run(); return null; }, null);
    }
}
