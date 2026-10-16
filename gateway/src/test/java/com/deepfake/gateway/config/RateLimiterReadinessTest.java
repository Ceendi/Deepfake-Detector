package com.deepfake.gateway.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterReadinessTest {
    @Test
    @SuppressWarnings("unchecked")
    void readinessWaitsForTheSynchronousWarmupListener() throws Exception {
        var template = mock(ReactiveStringRedisTemplate.class);
        RedisScript<List<Long>> script = mock(RedisScript.class);
        var entered = new CountDownLatch(1);
        var release = Sinks.<List<Long>>one();
        when(template.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.defer(() -> { entered.countDown(); return release.asMono().flux(); }));
        try (var context = new GenericApplicationContext(); var pool = Executors.newSingleThreadExecutor()) {
            var availability = new ApplicationAvailabilityBean();
            context.addApplicationListener(availability);
            context.addApplicationListener(new RateLimitConfig().rateLimiterWarmup(template, script));
            context.refresh();
            var listener = SpringFactoriesLoader.forDefaultResourceLocation().load(SpringApplicationRunListener.class,
                    SpringFactoriesLoader.ArgumentResolver.of(SpringApplication.class, new SpringApplication())
                            .and(String[].class, new String[0])).getFirst();
            var ready = pool.submit(() -> listener.ready(context, Duration.ZERO));
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
            } finally { release.tryEmitValue(List.of(1L, 0L)); }
            ready.get(3, TimeUnit.SECONDS);
            assertThat(availability.getReadinessState()).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        }
    }
}
