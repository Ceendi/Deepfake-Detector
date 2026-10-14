package com.deepfake.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * Runs the limiter script once before traffic. On a cold JVM the first scripted calls can exceed
 * the Redis command timeout, and the shared degradation window would then fail uploads closed.
 */
public final class RateLimiterWarmup {
    private static final Logger log = LoggerFactory.getLogger(RateLimiterWarmup.class);
    static final List<String> KEYS =
            List.of("request_rate_limiter.{warmup}.tokens", "request_rate_limiter.{warmup}.timestamp");

    private RateLimiterWarmup() { }

    public static Mono<Void> run(ReactiveStringRedisTemplate template, RedisScript<List<Long>> script,
                                 Duration timeout) {
        // Arguments: replenish rate, burst, current time (Redis TIME when empty), requested tokens.
        return Mono.defer(() -> template.execute(script, KEYS, List.of("1", "1", "", "1")).then())
                .timeout(timeout)
                .doOnError(error -> log.warn("Rate limiter warm-up failed: {}", error.toString()))
                .onErrorComplete();
    }
}
