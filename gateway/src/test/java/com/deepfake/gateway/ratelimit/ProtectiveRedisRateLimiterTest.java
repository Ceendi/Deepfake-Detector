package com.deepfake.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ProtectiveRedisRateLimiterTest {
    private final ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final RedisScript<List<Long>> script = mock(RedisScript.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong clock = new AtomicLong();
    private final RedisLimiterDegradation degradation = new RedisLimiterDegradation(Duration.ofSeconds(2), clock::get);
    private final ProtectiveRedisRateLimiter open = limiter(ProtectiveRedisRateLimiter.FailureMode.OPEN);
    private final ProtectiveRedisRateLimiter closed = limiter(ProtectiveRedisRateLimiter.FailureMode.CLOSED);

    @Test
    void sentinelOpensReadsClosesUploadAndBypassDoesNotCallRedisOrExtendWindow() {
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(1L, -1L)));
        assertOpen(open.isAllowed("route", "user").block());
        clock.set(Duration.ofSeconds(1).toNanos());
        assertClosed();
        assertOpen(open.isAllowed("route", "user").block());
        verify(template).execute(eq(script), anyList(), anyList());
        assertThat(meters.get("gateway.ratelimiter.degraded").tag("route", "route").counter().count()).isEqualTo(2);

        clock.set(Duration.ofSeconds(2).toNanos());
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(0L, 0L)));
        assertThat(open.isAllowed("route", "user").block().isAllowed()).isFalse();
        verify(template, times(2)).execute(eq(script), anyList(), anyList());
    }

    @Test
    void uploadSentinelStartsTheSameBypassWindowForReads() {
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(1L, -1L)));
        assertClosed();
        assertOpen(open.isAllowed("route", "user").block());
        verify(template).execute(eq(script), anyList(), anyList());
    }

    @Test
    void redisErrorConvertedBySpringToSentinelStillUsesHybridPolicy() {
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.error(new IllegalStateException("Redis down")));
        assertOpen(open.isAllowed("route", "user").block());
        assertClosed();
        verify(template).execute(eq(script), anyList(), anyList());
    }

    @Test
    void wholeCallTimeoutUsesHybridPolicyAndSharedBypass() {
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.never());
        StepVerifier.withVirtualTime(() -> open.isAllowed("route", "user"))
                .thenAwait(Duration.ofMillis(750))
                .assertNext(ProtectiveRedisRateLimiterTest::assertOpen).verifyComplete();
        assertClosed();
        verify(template).execute(eq(script), anyList(), anyList());
    }

    @Test
    void closedTimeoutStartsTheSharedBypassForReads() {
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.never());
        StepVerifier.withVirtualTime(() -> closed.isAllowed("route", "user"))
                .thenAwait(Duration.ofMillis(750))
                .expectErrorSatisfies(ProtectiveRedisRateLimiterTest::assertUnavailable).verify();
        assertOpen(open.isAllowed("route", "user").block());
        verify(template).execute(eq(script), anyList(), anyList());
    }

    @Test
    void healthyRedisRetainsQuotaDecisionsAndHeadersForBothPolicies() {
        when(template.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(1L, 1L)))
                .thenReturn(Flux.just(List.of(0L, 0L)));
        assertThat(open.isAllowed("route", "user").block().getHeaders()).containsEntry("X-RateLimit-Remaining", "1");
        assertThat(closed.isAllowed("route", "user").block().isAllowed()).isFalse();
        assertThat(degradation.bypassing()).isFalse();
        assertThat(meters.find("gateway.ratelimiter.degraded").counter()).isNull();
    }

    private ProtectiveRedisRateLimiter limiter(ProtectiveRedisRateLimiter.FailureMode mode) {
        var limiter = new ProtectiveRedisRateLimiter(template, script, null, Duration.ofMillis(750), degradation, mode, meters);
        limiter.getConfig().put("route", new RedisRateLimiter.Config().setReplenishRate(1).setBurstCapacity(2));
        return limiter;
    }

    private void assertClosed() {
        StepVerifier.create(closed.isAllowed("route", "user"))
                .expectErrorSatisfies(ProtectiveRedisRateLimiterTest::assertUnavailable).verify();
    }

    private static void assertUnavailable(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ResponseStatusException.class,
                status -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static void assertOpen(RedisRateLimiter.Response response) {
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getHeaders()).isEmpty();
    }
}
