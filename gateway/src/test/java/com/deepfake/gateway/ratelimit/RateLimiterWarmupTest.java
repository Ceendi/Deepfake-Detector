package com.deepfake.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Flux;

class RateLimiterWarmupTest {
    @SuppressWarnings("unchecked")
    private final RedisScript<List<Long>> script = mock(RedisScript.class);

    @Test
    void failedOrSlowWarmupNeverBlocksStartup() {
        var failing = mock(ReactiveStringRedisTemplate.class);
        when(failing.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("redis down")));
        assertThatCode(() -> RateLimiterWarmup.run(failing, script, Duration.ofSeconds(1)).block())
                .doesNotThrowAnyException();

        var hanging = mock(ReactiveStringRedisTemplate.class);
        when(hanging.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.never());
        assertThatCode(() -> RateLimiterWarmup.run(hanging, script, Duration.ofMillis(50)).block())
                .doesNotThrowAnyException();
    }
}
