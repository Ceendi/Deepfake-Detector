package com.deepfake.orchestrator.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmedMessageRecovererTest {
    final RabbitTemplate template = mock(RabbitTemplate.class);
    final AmqpRecoveryControl control = mock(AmqpRecoveryControl.class);
    final ConfirmedMessageRecoverer recoverer = new ConfirmedMessageRecoverer(template,
            RabbitConfig.Q_QUARANTINE, 50, control, RabbitConfig.DLQ_LISTENER);

    @Test void onlyConfirmedPersistentPublicationCompletesRecovery() {
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(true, null)); return null;
        }).when(template).send(eq(RabbitConfig.DLX), eq(RabbitConfig.Q_QUARANTINE), any(Message.class), any(CorrelationData.class));
        Message original = new Message(new byte[] {1}, new MessageProperties());
        original.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        recoverer.recover(original, new IllegalStateException("database"));
        assertThat(original.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        verify(control).succeeded(RabbitConfig.DLQ_LISTENER);
        verify(control, never()).publicationFailed(any());
    }
    @Test void timeoutOverridesDefaultRejectionWithImmediateRequeueAndPausesListener() {
        Message original = new Message(new byte[] {1}, new MessageProperties());
        assertThatThrownBy(() -> recoverer.recover(original, new IllegalStateException("database")))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
        verify(control).publicationFailed(RabbitConfig.DLQ_LISTENER);
        verify(control, never()).succeeded(any());
    }
    @Test void shutdownExceptionCannotLoseTheRequiredRequeue() {
        doThrow(new IllegalStateException("stop failure")).when(control).publicationFailed(any());
        assertThatThrownBy(() -> recoverer.recover(new Message(new byte[0], new MessageProperties()), new IllegalStateException("database")))
                .isInstanceOf(ImmediateRequeueAmqpException.class)
                .satisfies(failure -> assertThat(failure.getCause().getSuppressed()).hasSize(1));
    }
}
