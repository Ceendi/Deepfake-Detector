package com.deepfake.orchestrator.listener;

import com.deepfake.orchestrator.config.AmqpRecoveryControl;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** AUTO ack occurs after the source-specific database transaction commits or quarantine is confirmed. */
@Component
@RequiredArgsConstructor
public class DeadLetterListener {
    private final AnalysisService analysisService;
    private final MessageConverter converter;
    private final AmqpRecoveryControl recovery;

    @RabbitListener(id = RabbitConfig.DLQ_LISTENER,
            queues = {RabbitConfig.Q_RESULTS_DLQ, RabbitConfig.Q_VIDEO_DLQ, RabbitConfig.Q_AUDIO_DLQ},
            containerFactory = "deadLetterListenerFactory")
    public void onDead(Message message) {
        Map<?, ?> payload;
        UUID id;
        String source;
        try {
            Object decoded = converter.fromMessage(message);
            if (!(decoded instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected object payload");
            payload = map;
            id = UUID.fromString((String) payload.get("analysis_id"));
            String queue = message.getMessageProperties().getConsumerQueue();
            if (RabbitConfig.Q_RESULTS_DLQ.equals(queue)) {
                Object origin = message.getMessageProperties().getHeaders().get("x-original-routingKey");
                Object legacyOrigin = message.getMessageProperties().getHeaders().get("x-original-routing-key");
                Object status = payload.get("status");
                // Upgrade safety: the previous shared recoverer could already have parked
                // advisory progress here. Never reinterpret it as an unaccepted source failure.
                if (RabbitConfig.Q_PROGRESS.equals(origin) || RabbitConfig.Q_PROGRESS.equals(legacyOrigin)
                        || !("COMPLETED".equals(status) || "FAILED".equals(status))
                        || ("COMPLETED".equals(status) && !(payload.get("result") instanceof Map<?, ?>))) {
                    throw new IllegalArgumentException("Dead letter is not a terminal result");
                }
            }
            source = switch (queue) {
                case RabbitConfig.Q_VIDEO_DLQ -> "video";
                case RabbitConfig.Q_AUDIO_DLQ -> "audio";
                case RabbitConfig.Q_RESULTS_DLQ -> (String) payload.get("source");
                default -> throw new IllegalArgumentException("Unexpected dead-letter queue");
            };
            if (!("video".equals(source) || "audio".equals(source))
                    || (payload.get("source") != null && !source.equals(payload.get("source")))) {
                throw new IllegalArgumentException("Missing or inconsistent source");
            }
        } catch (RuntimeException invalidPayload) {
            throw new PoisonDeadLetterException("Invalid terminal dead-letter payload", invalidPayload);
        }
        Object correlation = payload.get("correlation_id");
        if (correlation != null) MDC.put("correlationId", correlation.toString());
        try {
            Object reason = message.getMessageProperties().getHeaders().get("x-exception-message");
            analysisService.failFromDlq(id, source, reason == null ? "dead-lettered" : reason.toString());
            recovery.succeeded(RabbitConfig.DLQ_LISTENER);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
