package com.deepfake.orchestrator.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.deepfake.orchestrator.service.AnalysisService;
import com.rabbitmq.client.Channel;

/** Manual ack: ack after a successful FAILED transition, otherwise nack with requeue=false (no loop). */
@ExtendWith(MockitoExtension.class)
class DeadLetterListenerTest {

    @Mock AnalysisService analysisService;
    @Mock Channel channel;

    DeadLetterListener listener;

    private static final long TAG = 7L;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeadLetterListener(analysisService);
    }

    @Test
    void marksFailedThenAcks() throws IOException {
        listener.onDead(payload(id.toString()), channel, TAG, "boom");

        verify(analysisService).failFromDlq(id, "boom");
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void dropsMessageWithoutAnalysisId() throws IOException {
        listener.onDead(payload(null), channel, TAG, "boom");

        verify(analysisService, never()).failFromDlq(any(), any());
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    void nacksWithoutRequeueWhenHandlingThrows() throws IOException {
        doThrow(new RuntimeException("db down")).when(analysisService).failFromDlq(eq(id), any());

        listener.onDead(payload(id.toString()), channel, TAG, "boom");

        verify(channel).basicNack(TAG, false, false);
    }

    private Map<String, Object> payload(String analysisId) {
        Map<String, Object> p = new HashMap<>();
        p.put("correlation_id", "cid-1");
        if (analysisId != null) {
            p.put("analysis_id", analysisId);
        }
        return p;
    }
}
