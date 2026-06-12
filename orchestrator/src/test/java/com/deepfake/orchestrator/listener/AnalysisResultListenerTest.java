package com.deepfake.orchestrator.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.deepfake.orchestrator.service.AnalysisService;

/**
 * D2 correlation: the detector-echoed correlation_id must be in MDC while the handler runs (so
 * listener logs join the originating request's chain) and gone afterwards (pooled thread).
 */
class AnalysisResultListenerTest {

    private final AnalysisService analysisService = mock(AnalysisService.class);
    private final AnalysisResultListener listener = new AnalysisResultListener(analysisService);

    @Test
    void bindsCorrelationIdToMdcWhileHandlingResult() {
        String[] seenInHandler = new String[1];
        doAnswer(inv -> seenInHandler[0] = MDC.get("correlationId"))
                .when(analysisService).handleResult(any());

        listener.onResult(Map.of("analysis_id", "a-1", "correlation_id", "cid-123"));

        assertThat(seenInHandler[0]).isEqualTo("cid-123");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void bindsCorrelationIdToMdcWhileHandlingProgress() {
        String[] seenInHandler = new String[1];
        doAnswer(inv -> seenInHandler[0] = MDC.get("correlationId"))
                .when(analysisService).handleProgress(any());

        listener.onProgress(Map.of("analysis_id", "a-1", "correlation_id", "cid-456"));

        assertThat(seenInHandler[0]).isEqualTo("cid-456");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void toleratesMissingCorrelationId() {
        listener.onResult(Map.of("analysis_id", "a-1"));

        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void clearsMdcEvenWhenHandlerThrows() {
        doAnswer(inv -> { throw new RuntimeException("boom"); })
                .when(analysisService).handleResult(any());

        try {
            listener.onResult(Map.of("analysis_id", "a-1", "correlation_id", "cid-789"));
        } catch (RuntimeException expected) {
            // rethrown for the container's retry/recoverer policy
        }

        assertThat(MDC.get("correlationId")).isNull();
    }
}
