package com.deepfake.orchestrator.config;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;

class AmqpRecoveryControlTest {
    @Test void databasePauseUsesOneThenTwoSecondDelayAndResetsAfterSuccess() {
        RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        @SuppressWarnings("unchecked") ObjectProvider<RabbitListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        when(registry.getListenerContainer("results")).thenReturn(container);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(container).stop(any(Runnable.class));
        try (var control = new AmqpRecoveryControl(provider, Duration.ofMillis(10), Duration.ofMillis(20))) {
            control.databaseFailed("results");
            await().during(Duration.ofMillis(700)).atMost(Duration.ofMillis(900))
                    .untilAsserted(() -> verify(container, never()).start());
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(container).start());
            control.databaseFailed("results");
            await().during(Duration.ofMillis(1400)).atMost(Duration.ofMillis(1700))
                    .untilAsserted(() -> verify(container, times(1)).start());
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(container, times(2)).start());
            control.succeeded("results");
            control.databaseFailed("results");
            await().atMost(Duration.ofMillis(1800)).untilAsserted(() -> verify(container, times(3)).start());
        }
    }
    @Test void restartWaitsForShutdownCallbackAndFailuresRemainVisibleUntilDeliverySucceeds() throws Exception {
        RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        @SuppressWarnings("unchecked") ObjectProvider<RabbitListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        when(registry.getListenerContainer("results")).thenReturn(container);
        AtomicReference<Runnable> callback = new AtomicReference<>();
        doAnswer(invocation -> { callback.set(invocation.getArgument(0)); return null; }).when(container).stop(any(Runnable.class));
        try (AmqpRecoveryControl control = new AmqpRecoveryControl(provider, Duration.ofMillis(50), Duration.ofMillis(100))) {
            control.publicationFailed("results"); control.publicationFailed("results");
            verify(container, times(1)).stop(any(Runnable.class));
            assertThat(control.health().getStatus()).isEqualTo(Status.DOWN);
            await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(1)).untilAsserted(() -> verify(container, never()).start());
            control.succeeded("results"); // concurrent healthy delivery must not cancel pending resume
            assertThat(control.health().getStatus()).isEqualTo(Status.DOWN);
            callback.get().run();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(container).start());
            assertThat(control.health().getStatus()).isEqualTo(Status.DOWN);
            control.succeeded("results");
            assertThat(control.health().getStatus()).isEqualTo(Status.UP);
        }
    }
    @Test void shutdownFailureRetriesStopBeforeRestarting() {
        RabbitListenerEndpointRegistry registry = mock(RabbitListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        @SuppressWarnings("unchecked") ObjectProvider<RabbitListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(registry);
        when(registry.getListenerContainer("results")).thenReturn(container);
        doThrow(new IllegalStateException("shutdown failed")).doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run(); return null;
        }).when(container).stop(any(Runnable.class));
        try (AmqpRecoveryControl control = new AmqpRecoveryControl(provider, Duration.ofMillis(30), Duration.ofMillis(60))) {
            control.publicationFailed("results");
            assertThat(control.health().getStatus()).isEqualTo(Status.DOWN);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(container, times(2)).stop(any(Runnable.class)));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(container).start());
            control.succeeded("results");
            assertThat(control.health().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test void closedControlCannotScheduleNewRestarts() {
        @SuppressWarnings("unchecked") ObjectProvider<RabbitListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        var control = new AmqpRecoveryControl(provider, Duration.ofMillis(10), Duration.ofMillis(20));
        control.close(); control.publicationFailed("results");
        verifyNoInteractions(provider);
    }
}
