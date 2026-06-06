package com.deepfake.orchestrator.listener;

import com.deepfake.orchestrator.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisResultListener {

    private static final String MDC_KEY = "correlationId";

    private final AnalysisService analysisService;

    @RabbitListener(queues = "analysis.results")
    public void onResult(Map<String, Object> payload) {
        withCorrelation(payload, () -> analysisService.handleResult(payload));
    }

    @RabbitListener(queues = "analysis.progress")
    public void onProgress(Map<String, Object> payload) {
        // Day 1: log + write to Redis under progress:{analysis_id}.
        // Frontend polls GET /api/analysis/{id}; once we add SSE, we will push instead.
        withCorrelation(payload, () -> analysisService.handleProgress(payload));
    }

    // Carry the request's correlation_id (echoed by the detector) into MDC so listener logs join
    // the same chain as the originating HTTP request.
    private void withCorrelation(Map<String, Object> payload, Runnable body) {
        Object cid = payload.get("correlation_id");
        if (cid != null) {
            MDC.put(MDC_KEY, cid.toString());
        }
        try {
            body.run();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
