package com.deepfake.gateway.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

/**
 * Registers {@code gateway.ratelimiter.degraded} at 0 for every route behind the fail-open limiter,
 * so the series exist before the first Redis outage instead of reading "No data"
 * (docs/contracts/metrics.md).
 */
@Component
public class DegradedRequestMeters implements MeterBinder {
    static final String FAIL_OPEN_LIMITER = "#{@availableRedisRateLimiter}";

    private final GatewayProperties gateway;

    public DegradedRequestMeters(GatewayProperties gateway) {
        this.gateway = gateway;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (RouteDefinition route : gateway.getRoutes()) {
            boolean failOpen = route.getFilters().stream().anyMatch(filter ->
                    "RequestRateLimiter".equals(filter.getName())
                            && filter.getArgs().containsValue(FAIL_OPEN_LIMITER));
            if (failOpen) {
                registry.counter("gateway.ratelimiter.degraded", "route", route.getId());
            }
        }
    }
}
