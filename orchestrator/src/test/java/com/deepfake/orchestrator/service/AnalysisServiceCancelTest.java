package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Cancel semantics: owner cancels an in-progress analysis (CANCELLED + slot freed + cancel event +
 * SSE close); IDOR -> 404; finished -> 409; repeat cancel -> idempotent 200; and a late detector
 * result on a CANCELLED analysis is ignored (no overwrite, no double release).
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceCancelTest {

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
    void ownerCancelsProcessingAnalysis() {
        Analysis processing = analysis("alice", AnalysisStatus.PROCESSING, AnalysisType.VIDEO);
        Analysis cancelled = analysis("alice", AnalysisStatus.CANCELLED, AnalysisType.VIDEO);
        when(repository.findById(id)).thenReturn(Optional.of(processing), Optional.of(cancelled));
        when(repository.cancelIfActive(eq(id), eq(AnalysisStatus.CANCELLED), any(), any())).thenReturn(1);

        AnalysisResponse response = service.cancel(id, "alice");

        assertThat(response.status()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(backpressure).release();
        verify(rabbitTemplate).convertAndSend(eq(RabbitConfig.EXCHANGE), eq(RabbitConfig.Q_CANCEL), any(Map.class));
        verify(streams).sendResult(eq(id), any());
        verify(streams).complete(id);
    }

    @Test
    void lostRaceToCompletingResultConflicts409() {
        // cancelIfActive matches 0 rows because a result completed the analysis first; cancel must 409.
        Analysis processing = analysis("alice", AnalysisStatus.PROCESSING, AnalysisType.VIDEO);
        Analysis completed = analysis("alice", AnalysisStatus.COMPLETED, AnalysisType.VIDEO);
        when(repository.findById(id)).thenReturn(Optional.of(processing), Optional.of(completed));
        when(repository.cancelIfActive(eq(id), eq(AnalysisStatus.CANCELLED), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
        verify(backpressure, never()).release();
        verifyNoInteractions(rabbitTemplate, streams);
    }

    @Test
    void foreignUserGets404AndNoSideEffects() {
        Analysis a = analysis("alice", AnalysisStatus.PROCESSING, AnalysisType.VIDEO);
        when(repository.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.cancel(id, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        verify(repository, never()).cancelIfActive(any(), any(), any(), any());
        verifyNoInteractions(backpressure, rabbitTemplate, streams);
    }

    @Test
    void finishedAnalysisConflicts409() {
        Analysis a = analysis("alice", AnalysisStatus.COMPLETED, AnalysisType.VIDEO);
        when(repository.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.cancel(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
        verify(repository, never()).cancelIfActive(any(), any(), any(), any());
        verify(backpressure, never()).release();
    }

    @Test
    void repeatCancelIsIdempotent() {
        Analysis a = analysis("alice", AnalysisStatus.CANCELLED, AnalysisType.VIDEO);
        when(repository.findById(id)).thenReturn(Optional.of(a));

        AnalysisResponse response = service.cancel(id, "alice");

        assertThat(response.status()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(repository, never()).cancelIfActive(any(), any(), any(), any());
        verify(backpressure, never()).release();
        verifyNoInteractions(rabbitTemplate, streams);
    }

    @Test
    void missingAnalysisGets404() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void lateResultOnCancelledIsIgnored() {
        // writeVideoProb's CAS matches 0 rows on a terminal analysis (the mock default), so the late
        // result is ignored without re-reading, completing, or releasing the slot.
        service.handleResult(Map.of(
                "analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8")));

        verify(repository, never()).complete(any(), any(), any(), any(), any(), any());
        verify(backpressure, never()).release();
        verify(streams, never()).sendResult(any(), any());
    }

    private Analysis analysis(String userId, AnalysisStatus status, AnalysisType type) {
        return Analysis.builder().id(id).userId(userId).status(status).type(type).build();
    }
}
