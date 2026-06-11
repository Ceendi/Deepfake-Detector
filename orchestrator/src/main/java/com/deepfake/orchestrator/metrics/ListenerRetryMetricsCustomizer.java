package com.deepfake.orchestrator.metrics;

import java.time.Duration;

import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts in-process redeliveries scheduled by the AMQP listener retry policy (D6) —
 * {@code amqp_listener_retries_total} in Prometheus (see docs/contracts/metrics.md).
 *
 * <p>Boot 4.0's listener retry exposes no RetryListener hook (the stateless interceptor builds its
 * own core RetryTemplate), so the policy's BackOff is wrapped via the settings factory instead.
 * RetryTemplate redelivers exactly when {@code nextBackOff() != STOP} — counting there is one
 * increment per actual retry; {@code shouldRetry} also fires on the exhausted final attempt and
 * would overcount by one.
 */
@Component
public class ListenerRetryMetricsCustomizer implements RabbitListenerRetrySettingsCustomizer {

    private final Counter retries;

    public ListenerRetryMetricsCustomizer(MeterRegistry registry) {
        // eager registration: the series exists at 0 before the first retry (rate()/alert-safe)
        this.retries = registry.counter("amqp.listener.retries");
    }

    @Override
    public void customize(RetryPolicySettings settings) {
        settings.setFactory(builder -> counting(builder.build()));
    }

    private RetryPolicy counting(RetryPolicy delegate) {
        return new RetryPolicy() {
            @Override
            public boolean shouldRetry(Throwable throwable) {
                return delegate.shouldRetry(throwable);
            }

            @Override
            public Duration getTimeout() {
                return delegate.getTimeout();
            }

            @Override
            public BackOff getBackOff() {
                BackOff backOff = delegate.getBackOff();
                return () -> {
                    BackOffExecution execution = backOff.start();
                    return () -> {
                        long next = execution.nextBackOff();
                        if (next != BackOffExecution.STOP) {
                            retries.increment();
                        }
                        return next;
                    };
                };
            }
        };
    }
}
