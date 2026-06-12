package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.request.AnalysisMode;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Audio mode propagation: the analysis.audio task carries the requested mode (default accurate),
 * while the analysis.video task keeps the unchanged contract — no mode key.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceModeTest {

    @Mock
    AnalysisRepository repository;
    @Mock
    RabbitTemplate rabbitTemplate;
    @Mock
    StringRedisTemplate redis;
    @Mock
    AnalysisCache cache;
    @Mock
    AnalysisStreamRegistry streams;
    @Mock
    BackpressureGuard backpressure;
    @Mock
    IdempotencyGuard idempotency;
    @Mock
    AnalysisMetrics metrics;
    @InjectMocks
    AnalysisService service;

    @Captor
    ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private final UUID id = UUID.randomUUID();

    @Test
    void audioTaskCarriesRequestedMode() {
        givenSavedAnalysis(AnalysisType.AUDIO);

        service.create(new CreateAnalysisRequest("f", "k", AnalysisType.AUDIO, AnalysisMode.FAST), "alice");

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.Q_AUDIO), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("mode", "fast");
    }

    @Test
    void missingModeDefaultsToAccurate() {
        givenSavedAnalysis(AnalysisType.AUDIO);

        service.create(new CreateAnalysisRequest("f", "k", AnalysisType.AUDIO, null), "alice");

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.Q_AUDIO), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("mode", "accurate");
    }

    @Test
    void fullAnalysisAddsModeOnlyToAudioTask() {
        givenSavedAnalysis(AnalysisType.FULL);

        service.create(new CreateAnalysisRequest("f", "k", AnalysisType.FULL, AnalysisMode.FAST), "alice");

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.Q_VIDEO), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).doesNotContainKey("mode");

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.Q_AUDIO), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).containsEntry("mode", "fast");
    }

    private void givenSavedAnalysis(AnalysisType type) {
        when(repository.save(any())).thenReturn(
                Analysis.builder().id(id).userId("alice").type(type).build());
    }
}
