package com.deepfake.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
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
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** The terminal SSE push must fire on afterCommit, not inside the tx — a rollback can't leak it. */
@ExtendWith(MockitoExtension.class)
class AnalysisServicePushAfterCommitTest {

    @Mock AnalysisRepository repository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redis;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void pushesOnlyAfterCommit() {
        givenAnalysis();
        TransactionSynchronizationManager.initSynchronization();

        service.handleResult(completedVideo());

        verifyNoInteractions(streams); // registered, not yet fired

        fireAfterCommit();

        verify(streams).sendResult(eq(id), any(AnalysisResultEvent.class));
        verify(streams).complete(id);
    }

    @Test
    void doesNotPushOnRollback() {
        givenAnalysis();
        TransactionSynchronizationManager.initSynchronization();

        service.handleResult(completedVideo());

        TransactionSynchronizationManager.clearSynchronization(); // rollback: cleared, afterCommit never runs

        verify(streams, never()).sendResult(any(), any());
        verify(streams, never()).complete(any());
    }

    private void fireAfterCommit() {
        List<TransactionSynchronization> syncs =
                TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
    }

    private void givenAnalysis() {
        Analysis a = Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO)
                .status(AnalysisStatus.PENDING).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
    }

    private Map<String, Object> completedVideo() {
        return Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8"));
    }
}
