package com.deepfake.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.util.backoff.BackOffExecution;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ListenerRetryMetricsCustomizerTest {

    // One increment per scheduled redelivery (non-STOP backoff), nothing on exhaustion: maxRetries=2
    // gives two backoffs then STOP, so the counter must read exactly 2.
    @Test
    void countsOneIncrementPerScheduledRedelivery() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ListenerRetryMetricsCustomizer customizer = new ListenerRetryMetricsCustomizer(registry);

        RetryPolicySettings settings = new RetryPolicySettings();
        settings.setMaxRetries(2L);
        settings.setDelay(Duration.ZERO);
        customizer.customize(settings);
        RetryPolicy policy = settings.createRetryPolicy();

        BackOffExecution execution = policy.getBackOff().start();
        assertThat(execution.nextBackOff()).isNotEqualTo(BackOffExecution.STOP);
        assertThat(execution.nextBackOff()).isNotEqualTo(BackOffExecution.STOP);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);

        assertThat(registry.get("amqp.listener.retries").counter().count()).isEqualTo(2.0);
    }

    @Test
    void counterExistsAtZeroBeforeAnyRetry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ListenerRetryMetricsCustomizer(registry);

        assertThat(registry.get("amqp.listener.retries").counter().count()).isZero();
    }

    // The wrap must not change retry decisions — exception include/exclude filtering still applies.
    @Test
    void delegatesShouldRetryToTheConfiguredPolicy() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ListenerRetryMetricsCustomizer customizer = new ListenerRetryMetricsCustomizer(registry);

        RetryPolicySettings settings = new RetryPolicySettings();
        settings.setExceptionExcludes(List.of(IllegalStateException.class));
        customizer.customize(settings);
        RetryPolicy policy = settings.createRetryPolicy();

        assertThat(policy.shouldRetry(new RuntimeException("transient"))).isTrue();
        assertThat(policy.shouldRetry(new IllegalStateException("fatal"))).isFalse();
    }
}
