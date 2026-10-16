package com.deepfake.orchestrator.config;

import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import lombok.extern.slf4j.Slf4j;

/** Dependency outages retain the original queued, without immediate redelivery loops. */
@Slf4j
public class AmqpRecoveryControl implements HealthIndicator, AutoCloseable {
    private final ObjectProvider<RabbitListenerEndpointRegistry> registry;
    private final long initialDelayMs;
    private final long maxDelayMs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "amqp-recovery-resume");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lifecycleLock = new Object();
    private final Map<String, State> paused = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AmqpRecoveryControl(ObjectProvider<RabbitListenerEndpointRegistry> registry,
            Duration initialDelay, Duration maxDelay) {
        if (initialDelay.toMillis() < 1 || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("AMQP recovery delays must be positive and ordered");
        }
        this.registry = registry;
        this.initialDelayMs = initialDelay.toMillis();
        this.maxDelayMs = maxDelay.toMillis();
    }

    public void publicationFailed(String listenerId) {
        failed(listenerId, initialDelayMs, maxDelayMs);
    }

    public void databaseFailed(String listenerId) {
        failed(listenerId, 1000L, 30_000L);
    }

    private void failed(String listenerId, long initialDelay, long maxDelay) {
        if (closed.get()) return;
        State state;
        long generation;
        synchronized (lifecycleLock) {
            state = paused.computeIfAbsent(listenerId, ignored -> new State(initialDelay, maxDelay));
            if (state.stopping) return;
            if (state.maxDelayMs != maxDelay) {
                state.nextDelayMs = initialDelay;
                state.maxDelayMs = maxDelay;
            }
            state.stopping = true;
            state.starting = false;
            state.successDuringStart = false;
            generation = ++state.generation;
        }
        stop(listenerId, state, generation);
    }

    private void stop(String listenerId, State state, long generation) {
        if (!current(listenerId, state, generation)) return;
        try {
            MessageListenerContainer container = registry.getObject().getListenerContainer(listenerId);
            if (container == null) throw new IllegalStateException("Missing listener " + listenerId);
            // forceStop avoids basicCancel's synchronous RPC on the failing consumer thread.
            // Restart is scheduled only by the completed shutdown callback, never stop's return.
            container.stop(() -> scheduleResume(listenerId, state, generation, container));
        } catch (RuntimeException failure) {
            log.warn("Could not stop listener {}; retrying shutdown before any resume", listenerId, failure);
            schedule(state, () -> stop(listenerId, state, generation));
        }
    }

    private void scheduleResume(String listenerId, State state, long generation, MessageListenerContainer container) {
        if (!current(listenerId, state, generation)) return;
        // A callback can be repeated by an unusual lifecycle implementation; schedule only once.
        synchronized (lifecycleLock) {
            if (state.scheduledGeneration == generation) return;
            state.scheduledGeneration = generation;
        }
        schedule(state, () -> {
            synchronized (lifecycleLock) {
                if (!current(listenerId, state, generation)) return;
                state.stopping = false;
                state.starting = true;
            }
            try {
                container.start();
                synchronized (lifecycleLock) {
                    if (state.generation != generation) return;
                    state.starting = false;
                    if (state.successDuringStart) paused.remove(listenerId, state);
                }
                // A live connection alone does not prove the queue resumed processing. Stay DOWN
                // until a delivery or a recovery publication succeeds, including during start().
            } catch (RuntimeException failure) {
                log.warn("Could not resume listener {}", listenerId, failure);
                publicationFailed(listenerId);
            }
        });
    }

    private boolean current(String listenerId, State state, long generation) {
        synchronized (lifecycleLock) {
            return !closed.get() && paused.get(listenerId) == state && state.generation == generation;
        }
    }

    private void schedule(State state, Runnable action) {
        if (closed.get()) return;
        long delay;
        synchronized (lifecycleLock) {
            delay = state.nextDelayMs;
            state.nextDelayMs = Math.min(state.maxDelayMs, delay * 2);
        }
        log.warn("AMQP listener dependency unavailable; next lifecycle attempt in {} ms", delay);
        scheduler.schedule(action, delay, TimeUnit.MILLISECONDS);
    }

    public void succeeded(String listenerId) {
        synchronized (lifecycleLock) {
            State state = paused.get(listenerId);
            if (state == null) return;
            // Another concurrent delivery cannot erase shutdown/resume work. A success while
            // starting is applied after start returns, if this generation has not failed again.
            if (state.stopping) return;
            if (state.starting) state.successDuringStart = true;
            else paused.remove(listenerId, state);
        }
    }

    @Override
    public Health health() {
        synchronized (lifecycleLock) {
            return paused.isEmpty() ? Health.up().build()
                    : Health.down().withDetail("pausedListeners", paused.keySet().stream().sorted().toList()).build();
        }
    }

    @Override
    public void close() {
        closed.set(true);
        scheduler.shutdownNow();
    }

    private static final class State {
        boolean stopping;
        boolean starting;
        boolean successDuringStart;
        long generation;
        long scheduledGeneration;
        long nextDelayMs;
        long maxDelayMs;
        State(long delay, long maxDelay) { nextDelayMs = delay; maxDelayMs = maxDelay; }
    }
}
