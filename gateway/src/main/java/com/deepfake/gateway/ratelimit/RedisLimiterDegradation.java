package com.deepfake.gateway.ratelimit;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Both route policies share an outage window so uploads also avoid repeated Redis waits. */
public class RedisLimiterDegradation {
    private static final Logger log = LoggerFactory.getLogger(RedisLimiterDegradation.class);
    private final long bypassNanos;
    private final LongSupplier clock;
    private Long failedAt;

    public RedisLimiterDegradation(Duration bypass) {
        this(bypass, System::nanoTime);
    }

    RedisLimiterDegradation(Duration bypass, LongSupplier clock) {
        if (bypass.isZero() || bypass.isNegative()) {
            throw new IllegalArgumentException("Redis limiter degraded bypass must be positive");
        }
        this.bypassNanos = bypass.toNanos();
        this.clock = clock;
    }

    public synchronized boolean bypassing() {
        return failedAt != null && clock.getAsLong() - failedAt < bypassNanos;
    }

    public synchronized void recordFailure(String routeId) {
        // Concurrent failures must neither extend the window nor flood the warning log.
        if (bypassing()) return;
        failedAt = clock.getAsLong();
        log.warn("Redis rate limiter unavailable on route {}; bypassing Redis briefly", routeId);
    }
}
