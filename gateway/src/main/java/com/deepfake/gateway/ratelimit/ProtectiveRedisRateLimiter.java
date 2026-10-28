package com.deepfake.gateway.ratelimit;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Upload quotas fail closed; other routes retain availability during a Redis outage. */
public class ProtectiveRedisRateLimiter extends RedisRateLimiter {
    public enum FailureMode { CLOSED, OPEN }

    private final Duration budget;
    private final RedisLimiterDegradation degradation;
    private final FailureMode failureMode;
    private final MeterRegistry meters;

    public ProtectiveRedisRateLimiter(ReactiveStringRedisTemplate template, RedisScript<List<Long>> script,
                                     ConfigurationService configurationService, Duration budget,
                                     RedisLimiterDegradation degradation, FailureMode failureMode,
                                     MeterRegistry meters) {
        super(template, script, configurationService);
        if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Limiter budget must be positive");
        this.budget = budget;
        this.degradation = degradation;
        this.failureMode = failureMode;
        this.meters = meters;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        return Mono.defer(() -> {
            if (degradation.bypassing()) return fallback(routeId);
            return Mono.defer(() -> super.isAllowed(routeId, id))
                    .timeout(budget)
                    .flatMap(response -> {
                        // SCG reports Redis errors through an allowed response with remaining=-1.
                        String remaining = response.getHeaders().get(getRemainingHeader());
                        if (remaining == null || "-1".equals(remaining)) return Mono.error(unavailable());
                        return Mono.just(response.isAllowed() ? response : withRetryAfter(response, remaining));
                    })
                    .onErrorResume(error -> {
                        degradation.recordFailure(routeId);
                        return fallback(routeId);
                    });
        });
    }

    // RequestRateLimiter copies these headers to the 429; clients wait exactly until one request fits.
    private Response withRetryAfter(Response denied, String remaining) {
        Map<String, String> headers = denied.getHeaders();
        long rate = Long.parseLong(headers.get(getReplenishRateHeader()));
        if (rate < 1) return denied; // a bucket that never refills has no retry time
        long missing = Long.parseLong(headers.get(getRequestedTokensHeader())) - Long.parseLong(remaining);
        Map<String, String> withRetryAfter = new HashMap<>(headers);
        withRetryAfter.put(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, Math.ceilDiv(missing, rate))));
        return new Response(false, withRetryAfter);
    }

    private Mono<Response> fallback(String routeId) {
        if (failureMode == FailureMode.CLOSED) return Mono.error(unavailable());
        meters.counter("gateway.ratelimiter.degraded", "route", routeId).increment();
        return Mono.just(new Response(true, Map.of()));
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter temporarily unavailable");
    }
}
