package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * AMQP result/progress handling pushes to the SSE registry: progress events while running, and
 * exactly one terminal result + complete() once a status is reached — but nothing until a multi-task
 * analysis is fully done.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceSsePushTest {

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
    @InjectMocks
    AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void handleProgressPushesProgressEvent() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        service.handleProgress(Map.of(
                "analysis_id", id.toString(), "source", "video",
                "progress", 50, "stage", "INFERENCE"));

        ArgumentCaptor<AnalysisProgressEvent> captor = ArgumentCaptor.forClass(AnalysisProgressEvent.class);
        verify(streams).sendProgress(eq(id), captor.capture());
        AnalysisProgressEvent event = captor.getValue();
        assertThat(event.analysisId()).isEqualTo(id.toString());
        assertThat(event.source()).isEqualTo("video");
        assertThat(event.progress()).isEqualTo(50);
        assertThat(event.stage()).isEqualTo("INFERENCE");
        assertThat(event.status()).isEqualTo("PROCESSING");
    }

    @Test
    void completedResultPushesResultThenCompletes() {
        givenAnalysis(AnalysisType.VIDEO);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8")));

        InOrder ordered = inOrder(streams);
        ordered.verify(streams).sendResult(eq(id), any(AnalysisResultEvent.class));
        ordered.verify(streams).complete(id);
    }

    @Test
    void failedResultPushesResultThenCompletes() {
        givenAnalysis(AnalysisType.VIDEO);

        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "FAILED",
                "error", Map.of("code", "X", "message", "boom")));

        InOrder ordered = inOrder(streams);
        ordered.verify(streams).sendResult(eq(id), any(AnalysisResultEvent.class));
        ordered.verify(streams).complete(id);
    }

    @Test
    void partialResultDoesNotPushUntilDone() {
        givenAnalysis(AnalysisType.FULL); // needs both video + audio

        service.handleResult(Map.of( // only video arrives so far
                "analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.7")));

        verify(streams, never()).sendResult(any(), any());
        verify(streams, never()).complete(any());
    }

    private void givenAnalysis(AnalysisType type) {
        Analysis a = Analysis.builder().id(id).userId("alice").type(type)
                .status(AnalysisStatus.PENDING).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
    }
}
