package com.deepfake.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class DegradedRequestMetersTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void failOpenRoutesFromTheApplicationConfigStartAtZero() throws IOException {
        new DegradedRequestMeters(applicationGatewayProperties()).bindTo(meters);

        assertThat(meters.find("gateway.ratelimiter.degraded").counters())
                .extracting(counter -> counter.getId().getTag("route"))
                .containsExactlyInAnyOrder("file-service", "orchestrator");
        assertThat(meters.find("gateway.ratelimiter.degraded").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void limiterFallbackIncrementsThePreRegisteredSeries() throws IOException {
        new DegradedRequestMeters(applicationGatewayProperties()).bindTo(meters);
        var degradation = new RedisLimiterDegradation(Duration.ofSeconds(2), () -> 0L);
        degradation.recordFailure("orchestrator");
        @SuppressWarnings("unchecked")
        RedisScript<List<Long>> script = mock(RedisScript.class);
        var limiter = new ProtectiveRedisRateLimiter(mock(ReactiveStringRedisTemplate.class), script, null,
                Duration.ofMillis(750), degradation, ProtectiveRedisRateLimiter.FailureMode.OPEN, meters);

        assertThat(limiter.isAllowed("orchestrator", "user").block().isAllowed()).isTrue();

        List<Meter> series = meters.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("gateway.ratelimiter.degraded")).toList();
        assertThat(series).hasSize(2);
        assertThat(meters.get("gateway.ratelimiter.degraded").tag("route", "orchestrator").counter().count())
                .isEqualTo(1);
    }

    private static GatewayProperties applicationGatewayProperties() throws IOException {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind(GatewayProperties.PREFIX, GatewayProperties.class)
                .orElseThrow(() -> new AssertionError("no gateway routes in application.yaml"));
    }
}
