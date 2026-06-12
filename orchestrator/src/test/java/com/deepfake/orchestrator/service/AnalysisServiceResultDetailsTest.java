package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * handleResult persists the full detector result, not just prob_fake: model_version, gradcam keys
 * and metadata land in the per-source details column written by the same atomic UPDATE as the prob.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceResultDetailsTest {

    @Mock AnalysisRepository repository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redis;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @Mock IdempotencyGuard idempotency;
    @Mock AnalysisMetrics metrics;
    @InjectMocks AnalysisService service;

    @Captor
    ArgumentCaptor<Map<String, Object>> detailsCaptor;

    private final UUID id = UUID.randomUUID();

    @Test
    void audioResultDetailsGoIntoAudioColumnWrite() {
        givenActiveAnalysis(AnalysisType.AUDIO);
        when(repository.writeAudioProb(eq(id), any(), any(), any(), any())).thenReturn(1);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "audio", "status", "COMPLETED",
                "result", Map.of(
                        "prob_fake", "0.91",
                        "model_version", "v1.2.0-fast",
                        "gradcam_url", "minio://analysis-artifacts/" + id + "/gradcam.png",
                        "metadata", Map.of("insights", List.of("podejrzane artefakty")))));

        verify(repository).writeAudioProb(eq(id), eq(new BigDecimal("0.91")),
                detailsCaptor.capture(), any(), any());
        assertThat(detailsCaptor.getValue())
                .containsEntry("modelVersion", "v1.2.0-fast")
                .containsEntry("gradcamKeys", List.of(id + "/gradcam.png"))
                .containsEntry("metadata", Map.of("insights", List.of("podejrzane artefakty")));
    }

    @Test
    void videoStubResultWithoutExtrasWritesNullDetails() {
        givenActiveAnalysis(AnalysisType.VIDEO);
        when(repository.writeVideoProb(eq(id), any(), isNull(), any(), any())).thenReturn(1);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.42", "gradcam_urls", List.of())));

        verify(repository).writeVideoProb(eq(id), eq(new BigDecimal("0.42")), isNull(), any(), any());
    }

    private void givenActiveAnalysis(AnalysisType type) {
        Analysis a = Analysis.builder().id(id).userId("alice").type(type)
                .status(AnalysisStatus.PROCESSING)
                .videoProb(type == AnalysisType.VIDEO ? new BigDecimal("0.42") : null)
                .audioProb(type == AnalysisType.AUDIO ? new BigDecimal("0.91") : null)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
        when(repository.complete(eq(id), eq(AnalysisStatus.COMPLETED), any(), any(), any(), any()))
                .thenReturn(1);
    }
}
