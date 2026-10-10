package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.cache.AnalysisCache;
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
    @Mock com.deepfake.orchestrator.repository.AnalysisTaskOutboxRepository outbox;
    @Spy
    OptionalRedisOperations optionalRedis =
            new OptionalRedisOperations(Duration.ofSeconds(2));

    @Mock ArtifactCleanupStore artifactCleanup;

    @InjectMocks
    AnalysisService service;

    @Captor
    ArgumentCaptor<com.deepfake.orchestrator.entity.AnalysisTaskOutbox> payloadCaptor;

    private final UUID id = UUID.randomUUID();

    @Test
    void audioTaskCarriesRequestedMode() {
        givenSavedAnalysis(AnalysisType.AUDIO);

        service.createResolved(new CreateAnalysisRequest("f", "k", AnalysisType.AUDIO, AnalysisMode.FAST), "alice");

        verify(outbox).save(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getPayload()).containsEntry("mode", "fast");
    }

    @Test
    void missingModeDefaultsToAccurate() {
        givenSavedAnalysis(AnalysisType.AUDIO);

        service.createResolved(new CreateAnalysisRequest("f", "k", AnalysisType.AUDIO, null), "alice");

        verify(outbox).save(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getPayload()).containsEntry("mode", "accurate");
    }

    @Test
    void fullAnalysisAddsModeOnlyToAudioTask() {
        givenSavedAnalysis(AnalysisType.FULL);

        service.createResolved(new CreateAnalysisRequest("f", "k", AnalysisType.FULL, AnalysisMode.FAST), "alice");

        verify(outbox, org.mockito.Mockito.times(2)).save(payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues().get(0).getPayload()).doesNotContainKey("mode");
        assertThat(payloadCaptor.getAllValues().get(1).getPayload()).containsEntry("mode", "fast");
    }

    private void givenSavedAnalysis(AnalysisType type) {
        when(repository.save(any())).thenReturn(
                Analysis.builder().id(id).userId("alice").type(type).build());
    }
}
