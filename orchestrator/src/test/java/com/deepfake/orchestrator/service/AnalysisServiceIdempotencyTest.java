package com.deepfake.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Idempotency: a result flagged as already-processed is skipped before any DB work; a fresh result
 * records the dedup key only on afterCommit, so a rollback-then-retry isn't mistaken for a duplicate.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceIdempotencyTest {

    @Mock AnalysisRepository repository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redis;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @Mock IdempotencyGuard idempotency;
    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void duplicateIsSkippedBeforeAnyDbWork() {
        when(idempotency.alreadyProcessed(id, "video")).thenReturn(true);

        service.handleResult(completedVideo());

        verify(repository, never()).findById(any());
        verify(repository, never()).save(any());
        verify(backpressure, never()).release();
        verify(idempotency, never()).markProcessed(any(), any());
    }

    @Test
    void freshResultMarksOnlyAfterCommit() {
        Analysis a = Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO)
                .status(AnalysisStatus.PENDING).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
        TransactionSynchronizationManager.initSynchronization();

        service.handleResult(completedVideo());

        verify(idempotency, never()).markProcessed(any(), any()); // not until the tx commits

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(idempotency).markProcessed(id, "video");
    }

    private Map<String, Object> completedVideo() {
        return Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8"));
    }
}
