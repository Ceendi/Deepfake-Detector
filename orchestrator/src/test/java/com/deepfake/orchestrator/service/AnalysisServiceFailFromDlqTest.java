package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** failFromDlq is idempotent: an active analysis goes FAILED; a terminal one is a no-op. */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceFailFromDlqTest {

    @Mock AnalysisRepository repository;
    @Mock com.deepfake.orchestrator.repository.AnalysisTaskOutboxRepository outbox;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @Mock AnalysisMetrics metrics;
    @Spy
    OptionalRedisOperations optionalRedis =
            new OptionalRedisOperations(Duration.ofSeconds(2));

    @Mock ArtifactCleanupStore artifactCleanup;

    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void activeAnalysisFailsAndPublishesTerminalState() {
        when(redis.opsForValue()).thenReturn(values);
        when(repository.failSourceIfUnaccepted(eq(id), eq("video"), eq(AnalysisStatus.FAILED), any(), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));

        service.failFromDlq(id, "video", "boom");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(repository).failSourceIfUnaccepted(eq(id), eq("video"), eq(AnalysisStatus.FAILED), msg.capture(), any(), any());
        assertThat(msg.getValue()).isEqualTo("[DEAD_LETTERED] Nie udało się zakończyć analizy.");
        verifyNoInteractions(backpressure);
        verify(streams).sendResult(eq(id), any());
        verify(values).set("cancel:" + id, "1", Duration.ofHours(2));
        verify(metrics).dlqFailure();
        verify(metrics, never()).stuckRecovery();
    }

    @Test
    void failStuckRechecksTheDeadlineAfterLocking() {
        when(redis.opsForValue()).thenReturn(values);
        when(repository.lockForRecovery(id)).thenReturn(Optional.of(id));
        when(repository.failIfExpired(eq(id), any(), any(), any(), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));
        service.failStuck(id, 600);
        verify(repository).failIfExpired(eq(id), any(), any(), any(), any(), any());
        verify(values).set("cancel:" + id, "1", Duration.ofHours(2));
        verify(metrics).stuckRecovery();
        verify(metrics, never()).dlqFailure();
    }

    @Test
    void redisOutageDoesNotFailTheCommittedFailure() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RedisConnectionFailureException("down"))
                .when(values).set("cancel:" + id, "1", Duration.ofHours(2));
        when(repository.failSourceIfUnaccepted(eq(id), eq("video"), eq(AnalysisStatus.FAILED), any(), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));
        service.failFromDlq(id, "video", "delivery_limit");
        verify(streams).sendResult(eq(id), any());
        verify(metrics).dlqFailure();
    }

    // CAS returns 0 for both already-terminal and unknown ids (the mock default), so the transition
    // is a no-op without re-reading or releasing.
    @Test
    void nonActiveAnalysisIsNoOp() {
        service.failFromDlq(id, "video", "boom");

        verify(repository).failSourceIfUnaccepted(eq(id), eq("video"), eq(AnalysisStatus.FAILED), any(), any(), any());
        verify(repository, never()).findById(any());
        verifyNoInteractions(backpressure);
        verify(streams, never()).sendResult(any(), any());
        verify(metrics, never()).dlqFailure();
    }

    private Analysis analysis(AnalysisStatus status) {
        return Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO).status(status).build();
    }
}
