package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Delete semantics (DELETE /api/analysis/{id}/record): the owner permanently removes a finished
 * analysis (row hard-deleted + cache evicted + residual Redis keys dropped + metric); IDOR -> 404;
 * an in-progress analysis -> 409 with no side effects (slot must be freed by cancel first); a Redis
 * outage must not fail a committed delete. Distinct from cancel (DELETE /{id}), which is left alone.
 *
 * <p>No Spring transaction here, so the after-commit Redis cleanup runs inline (same fallback the
 * cancel/idempotency after-commit helpers use) — which is exactly what lets us assert it fired.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceDeleteTest {

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
    void ownerDeletesCompletedAnalysis() {
        Analysis completed = analysis("alice", AnalysisStatus.COMPLETED);
        when(repository.findById(id)).thenReturn(Optional.of(completed));

        List<String> reclaimable = service.delete(id, "alice");

        assertThat(reclaimable).isEmpty(); // no detector details -> no Grad-CAM objects to reclaim
        verify(repository).delete(completed);
        verify(metrics).deleted(AnalysisStatus.COMPLETED);
        // After-commit cleanup (inline, no tx): cache evict is the correctness-critical part, the
        // dedup/progress/cancel keys are residual hygiene.
        verify(cache).evictById(id);
        verify(idempotency).clear(id);
        verify(redis).delete(List.of("progress:" + id, "cancel:" + id));
        verifyNoInteractions(rabbitTemplate, streams, backpressure);
    }

    @Test
    void returnsGradcamKeysFromBothSourcesForReclaim() {
        // The caller (controller) reclaims these object keys from analysis-artifacts after commit.
        Analysis a = Analysis.builder().id(id).userId("alice")
                .status(AnalysisStatus.COMPLETED).type(AnalysisType.FULL)
                .videoDetails(Map.of("gradcamKeys", List.of(id + "/video/f1.png", id + "/video/f2.png")))
                .audioDetails(Map.of("gradcamKeys", List.of(id + "/audio/a1.png")))
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(a));

        List<String> reclaimable = service.delete(id, "alice");

        assertThat(reclaimable).containsExactlyInAnyOrder(
                id + "/video/f1.png", id + "/video/f2.png", id + "/audio/a1.png");
        verify(repository).delete(a);
    }

    @Test
    void failedAnalysisIsDeletable() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis("alice", AnalysisStatus.FAILED)));

        service.delete(id, "alice");

        verify(repository).delete(any(Analysis.class));
        verify(metrics).deleted(AnalysisStatus.FAILED);
    }

    @Test
    void cancelledAnalysisIsDeletable() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis("alice", AnalysisStatus.CANCELLED)));

        service.delete(id, "alice");

        verify(repository).delete(any(Analysis.class));
        verify(metrics).deleted(AnalysisStatus.CANCELLED);
    }

    @Test
    void pendingAnalysisConflicts409AndIsNotDeleted() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis("alice", AnalysisStatus.PENDING)));

        assertThatThrownBy(() -> service.delete(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verify(repository, never()).delete(any());
        verifyNoInteractions(metrics, cache, idempotency, streams, backpressure);
    }

    @Test
    void processingAnalysisConflicts409AndIsNotDeleted() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis("alice", AnalysisStatus.PROCESSING)));

        assertThatThrownBy(() -> service.delete(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verify(repository, never()).delete(any());
        verifyNoInteractions(metrics, cache, idempotency);
    }

    @Test
    void foreignUserGets404AndNoSideEffects() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis("alice", AnalysisStatus.COMPLETED)));

        assertThatThrownBy(() -> service.delete(id, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

        verify(repository, never()).delete(any());
        verifyNoInteractions(metrics, cache, idempotency, streams, backpressure);
    }

    @Test
    void missingAnalysisGets404() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);

        verify(repository, never()).delete(any());
        verifyNoInteractions(metrics);
    }

    @Test
    void redisOutageDoesNotFailTheDelete() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis("alice", AnalysisStatus.COMPLETED)));
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyList());

        // Fail-open: the row is already gone from the DB; a residual progress/cancel key just TTLs out.
        assertThatCode(() -> service.delete(id, "alice")).doesNotThrowAnyException();

        verify(repository).delete(any(Analysis.class));
        verify(metrics).deleted(AnalysisStatus.COMPLETED);
    }

    private Analysis analysis(String userId, AnalysisStatus status) {
        return Analysis.builder().id(id).userId(userId).status(status).type(AnalysisType.VIDEO).build();
    }
}
