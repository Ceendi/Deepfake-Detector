package com.deepfake.orchestrator.sse;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnalysisStreamWatchdogTest {
    @ParameterizedTest
    @ValueSource(strings = {"heartbeat", "observe"})
    void oneFailureDoesNotStopLaterWatchdogTicks(String task) throws Exception {
        var ticks = new AtomicInteger();
        var continued = new CountDownLatch(1);
        var limits = new SseLimits(4, 4, 4, Duration.ofMinutes(10), Duration.ofMillis(20), Duration.ofSeconds(5));
        var registry = new AnalysisStreamRegistry(limits) {
            private void tick(String name) {
                if (!name.equals(task)) return;
                if (ticks.incrementAndGet() == 1) throw new IllegalStateException("one failed tick");
                continued.countDown();
            }
            @Override public void heartbeat() { tick("heartbeat"); super.heartbeat(); }
            @Override public void observe() { tick("observe"); super.observe(); }
        };
        try {
            registry.start();
            assertThat(continued.await(3, TimeUnit.SECONDS)).as(task + " resumes after an exception").isTrue();
            assertThat(ticks.get()).isGreaterThanOrEqualTo(2);
        } finally { registry.shutdown(); }
    }
}
