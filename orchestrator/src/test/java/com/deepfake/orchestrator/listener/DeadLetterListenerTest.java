package com.deepfake.orchestrator.listener;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.MDC;
import com.deepfake.orchestrator.config.AmqpRecoveryControl;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.service.AnalysisService;

class DeadLetterListenerTest {
    final AnalysisService service = mock(AnalysisService.class);
    final AmqpRecoveryControl recovery = mock(AmqpRecoveryControl.class);
    final JacksonJsonMessageConverter converter = new JacksonJsonMessageConverter();
    final DeadLetterListener listener = new DeadLetterListener(service, converter, recovery);
    final UUID id = UUID.randomUUID();

    @Test void taskDeadLetterInfersItsSourceWithoutChangingTaskPayload() {
        listener.onDead(message(RabbitConfig.Q_AUDIO_DLQ, Map.of("analysis_id", id.toString(), "correlation_id", "cid")));
        verify(service).failFromDlq(id, "audio", "boom");
        verify(recovery).succeeded(RabbitConfig.DLQ_LISTENER);
        assertThat(MDC.get("correlationId")).isNull();
    }
    @Test void resultDeadLetterPreservesSource() {
        listener.onDead(message(RabbitConfig.Q_RESULTS_DLQ, Map.of("analysis_id", id.toString(), "source", "video", "status", "FAILED")));
        verify(service).failFromDlq(id, "video", "boom");
    }
    @Test void invalidPayloadsArePoisonRatherThanDatabaseFailures() {
        assertThatThrownBy(() -> listener.onDead(message(RabbitConfig.Q_RESULTS_DLQ, Map.of("analysis_id", id.toString()))))
                .isInstanceOf(PoisonDeadLetterException.class);
        assertThatThrownBy(() -> listener.onDead(message(RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", "bad"))))
                .isInstanceOf(PoisonDeadLetterException.class);
        assertThatThrownBy(() -> listener.onDead(message(RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", id.toString(), "source", "audio"))))
                .isInstanceOf(PoisonDeadLetterException.class);
        verifyNoInteractions(service, recovery);
    }
    @Test void legacyProgressCannotBeReinterpretedAsTerminalEvenWithAStatusField() {
        Message message = message(RabbitConfig.Q_RESULTS_DLQ, Map.of("analysis_id", id.toString(), "source", "video", "status", "FAILED", "progress", 42));
        message.getMessageProperties().setHeader("x-original-routingKey", RabbitConfig.Q_PROGRESS);
        assertThatThrownBy(() -> listener.onDead(message)).isInstanceOf(PoisonDeadLetterException.class);
        verifyNoInteractions(service, recovery);
    }
    @Test void databaseFailurePropagatesToFiniteRetryPolicyWithoutAcknowledgingSuccess() {
        doThrow(new DataAccessResourceFailureException("offline")).when(service).failFromDlq(eq(id), eq("video"), any());
        assertThatThrownBy(() -> listener.onDead(message(RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", id.toString(), "correlation_id", "cid"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(recovery);
        assertThat(MDC.get("correlationId")).isNull();
    }
    private Message message(String queue, Map<String, Object> payload) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue(queue); properties.setHeader("x-exception-message", "boom");
        return converter.toMessage(payload, properties);
    }
}
