package com.deepfake.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Redis is required for protective quotas; dependency failure is 503, quota exhaustion is 429. */
public class ProtectiveRedisRateLimiter extends RedisRateLimiter {
    private final Duration budget;

    public ProtectiveRedisRateLimiter(ReactiveStringRedisTemplate template, RedisScript<List<Long>> script,
                                     ConfigurationService configurationService, Duration budget) {
        super(template, script, configurationService);
        if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Limiter budget must be positive");
        this.budget = budget;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        return Mono.defer(() -> super.isAllowed(routeId, id))
                .timeout(budget)
                .flatMap(response -> {
                    // SCG 5.0 reports its fail-open fallback with remaining=-1. Convert that
                    // sentinel before RequestRateLimiter can forward the unprotected request.
                    String remaining = response.getHeaders().get(getRemainingHeader());
                    if (remaining == null || "-1".equals(remaining)) return Mono.error(unavailable());
                    return Mono.just(response);
                })
                .onErrorMap(error -> error instanceof ResponseStatusException ? error : unavailable());
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter temporarily unavailable");
    }
}
