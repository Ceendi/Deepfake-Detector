package com.deepfake.orchestrator.listener;

import com.deepfake.orchestrator.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisResultListener {

    private final AnalysisService analysisService;

    @RabbitListener(queues = "analysis.results")
    public void onResult(Map<String, Object> payload) {
        analysisService.handleResult(payload);
    }

    @RabbitListener(queues = "analysis.progress")
    public void onProgress(Map<String, Object> payload) {
        // Day 1: log + write to Redis under progress:{analysis_id}.
        // Frontend polls GET /api/analysis/{id}; once we add WS, we will push instead.
        analysisService.handleProgress(payload);
    }
}
