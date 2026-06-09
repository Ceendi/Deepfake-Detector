package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.exception.TooManyAnalysesException;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Backpressure accounting: create() reserves a slot (and persists nothing when rejected); a terminal
 * result frees exactly one slot, but a partial multi-task result does not.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceBackpressureTest {

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

    private final UUID id = UUID.randomUUID();

    @Test
    void createAcquiresASlot() {
        when(repository.save(any())).thenReturn(
                Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO).build());

        service.create(new CreateAnalysisRequest("f", "k", AnalysisType.VIDEO), "alice");

        verify(backpressure).acquire();
    }

    @Test
    void rejectionPersistsAndPublishesNothing() {
        doThrow(new TooManyAnalysesException(21, 5)).when(backpressure).acquire();

        assertThatThrownBy(() ->
                service.create(new CreateAnalysisRequest("f", "k", AnalysisType.VIDEO), "alice"))
                .isInstanceOf(TooManyAnalysesException.class);

        verifyNoInteractions(repository);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void completedResultReleasesSlot() {
        givenAnalysis(AnalysisType.VIDEO, new BigDecimal("0.8"), null);
        when(repository.writeVideoProb(eq(id), any(), any(), any())).thenReturn(1);
        when(repository.complete(eq(id), eq(AnalysisStatus.COMPLETED), any(), any(), any(), any())).thenReturn(1);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8")));

        verify(backpressure).release();
    }

    @Test
    void failedResultReleasesSlot() {
        givenAnalysis(AnalysisType.VIDEO, null, null);
        when(repository.failIfActive(eq(id), eq(AnalysisStatus.FAILED), any(), any(), any())).thenReturn(1);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "FAILED",
                "error", Map.of("code", "X", "message", "boom")));

        verify(backpressure).release();
    }

    @Test
    void partialResultDoesNotReleaseUntilDone() {
        givenAnalysis(AnalysisType.FULL, new BigDecimal("0.7"), null); // still needs audio
        when(repository.writeVideoProb(eq(id), any(), any(), any())).thenReturn(1);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.7")));

        verify(backpressure, never()).release();
    }

    // The CAS write/complete is mocked, so seed the probs the post-write fresh read would observe.
    private void givenAnalysis(AnalysisType type, BigDecimal videoProb, BigDecimal audioProb) {
        Analysis a = Analysis.builder().id(id).userId("alice").type(type)
                .status(AnalysisStatus.PROCESSING).videoProb(videoProb).audioProb(audioProb).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
    }
}
