package com.deepfake.orchestrator.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptionalRedisOperationsTest {
    @Test
    void sharedFailureBypassesAllHintsBrieflyThenRetriesWithoutExtendingBySkippedCalls() {
        AtomicLong clock = new AtomicLong(100);
        AtomicLong calls = new AtomicLong();
        var operations = new OptionalRedisOperations(Duration.ofSeconds(2), clock::get);
        assertThat(operations.get("cache", () -> {
            calls.incrementAndGet();
            throw new RedisConnectionFailureException("down");
        }, "DB")).isEqualTo("DB");
        assertThat(operations.cacheNamespace()).isNotEmpty();
        String namespace = operations.cacheNamespace();
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        operations.run("cancel", calls::incrementAndGet);
        assertThat(operations.get("dedup", () -> { calls.incrementAndGet(); return true; }, false)).isFalse();
        assertThat(calls).hasValue(1);
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(operations.get("cache", () -> { calls.incrementAndGet(); return "Redis"; }, "DB")).isEqualTo("Redis");
        operations.run("mark", calls::incrementAndGet);
        assertThat(calls).hasValue(3);
        assertThat(operations.cacheNamespace()).isEqualTo(namespace);
    }

    @Test
    void unexpectedProgrammingFailuresAreNotHidden() {
        var operations = new OptionalRedisOperations(Duration.ofSeconds(2));
        assertThatThrownBy(() -> operations.run("bad action", () -> { throw new IllegalStateException("bug"); }))
                .isInstanceOf(IllegalStateException.class);
    }
}
