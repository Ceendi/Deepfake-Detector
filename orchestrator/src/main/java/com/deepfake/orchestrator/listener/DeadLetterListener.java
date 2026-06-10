package com.deepfake.orchestrator.listener;

import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.service.AnalysisService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the dead-letter queues so dead messages can't hang forever (D6): mark the analysis FAILED,
 * notify SSE and free its in-flight slot. The only literal manual ack in the orchestrator — a DLQ is
 * a terminus (no retry), so explicit ack/nack here can't conflict with the retry interceptor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterListener {

    private static final String MDC_KEY = "correlationId";

    private final AnalysisService analysisService;

    @RabbitListener(
            queues = {RabbitConfig.Q_RESULTS_DLQ, RabbitConfig.Q_VIDEO_DLQ, RabbitConfig.Q_AUDIO_DLQ},
            ackMode = "MANUAL")
    public void onDead(Map<String, Object> payload, Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long tag,
            @Header(name = "x-exception-message", required = false) String exMsg) throws IOException {
        Object cid = payload.get("correlation_id");
        if (cid != null) {
            MDC.put(MDC_KEY, cid.toString());
        }
        try {
            Object idRaw = payload.get("analysis_id");
            if (idRaw == null) {
                log.error("dead-letter without analysis_id, dropping: {}", payload);
                channel.basicNack(tag, false, false);
                return;
            }
            analysisService.failFromDlq(UUID.fromString(idRaw.toString()),
                    exMsg != null ? exMsg : "dead-lettered");
            channel.basicAck(tag, false);
        } catch (Exception e) {
            // Never requeue onto a DLQ — that loops. Dropping is better than spinning.
            log.error("DLQ handling failed for {}, dropping", payload, e);
            channel.basicNack(tag, false, false);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
