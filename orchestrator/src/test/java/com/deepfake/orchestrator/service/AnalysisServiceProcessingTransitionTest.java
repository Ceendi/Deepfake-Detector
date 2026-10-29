package com.deepfake.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * handleProgress drives the active transition through one atomic recordProgress update: an active
 * analysis (rows > 0) evicts the cache and pushes; a late ping on a terminal/unknown one (0 rows) is
 * dropped without a stale PROCESSING push.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceProcessingTransitionTest {

    @Mock AnalysisRepository repository;
    @Mock com.deepfake.orchestrator.repository.AnalysisTaskOutboxRepository outbox;
    @Mock StringRedisTemplate redis;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @Mock IdempotencyGuard idempotency;
    @Spy
    OptionalRedisOperations optionalRedis =
            new OptionalRedisOperations(Duration.ofSeconds(2));

    @Mock ArtifactCleanupStore artifactCleanup;

    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void activeProgressEvictsAndPushes() {
        when(repository.recordProgress(eq(id), eq(AnalysisStatus.PROCESSING), any(), any(Instant.class), eq("video")))
                .thenReturn(1);

        service.handleProgress(progress());

        verify(cache).evictById(id);
        verify(streams).sendProgress(eq(id), any());
        verifyNoInteractions(redis);
    }

    @Test
    void lateProgressOnTerminalIsDropped() {
        when(repository.recordProgress(eq(id), eq(AnalysisStatus.PROCESSING), any(), any(Instant.class), eq("video")))
                .thenReturn(0);

        service.handleProgress(progress());

        verify(cache, never()).evictById(any());
        verifyNoInteractions(streams);
        verify(redis, never()).opsForValue();
    }

    @Test
    void progressSideEffectsWaitForCommitAndRollbackEmitsNothing() {
        when(repository.recordProgress(eq(id), any(), any(), any(), eq("video"))).thenReturn(1);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.handleProgress(progress());
            verifyNoInteractions(cache, redis, streams);
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(callback -> callback.afterCompletion(
                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(cache, redis, streams);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void afterCommitDeliveryFailureDoesNotChangeDatabaseSuccess() {
        when(repository.recordProgress(eq(id), any(), any(), any(), eq("video"))).thenReturn(1);
        doThrow(new IllegalStateException("closed emitter")).when(streams).sendProgress(eq(id), any());
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.handleProgress(progress());
            verifyNoInteractions(cache, redis, streams);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(streams).sendProgress(eq(id), any());
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void databaseErrorPropagatesToAdvisoryRetryWithoutSideEffects() {
        when(repository.recordProgress(eq(id), any(), any(), any(), eq("video")))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.handleProgress(progress()))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verifyNoInteractions(cache, redis, streams);
    }

    private Map<String, Object> progress() {
        return Map.of("analysis_id", id.toString(), "source", "video", "progress", 0, "stage", "LOADING");
    }
}
