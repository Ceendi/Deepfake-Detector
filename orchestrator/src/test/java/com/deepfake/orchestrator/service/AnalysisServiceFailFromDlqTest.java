package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** failFromDlq is idempotent: an active analysis goes FAILED + releases once; a terminal one is a no-op. */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceFailFromDlqTest {

    @Mock AnalysisRepository repository;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redis;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void activeAnalysisFailsAndReleasesOnce() {
        Analysis a = analysis(AnalysisStatus.PROCESSING);
        when(repository.findById(id)).thenReturn(Optional.of(a));

        service.failFromDlq(id, "boom");

        ArgumentCaptor<Analysis> saved = ArgumentCaptor.forClass(Analysis.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage()).isEqualTo("dead-letter: boom");
        verify(backpressure).release();
        verify(streams).complete(id);
    }

    @Test
    void terminalAnalysisIsNoOp() {
        when(repository.findById(id)).thenReturn(Optional.of(analysis(AnalysisStatus.COMPLETED)));

        service.failFromDlq(id, "boom");

        verify(repository, never()).save(any());
        verify(backpressure, never()).release();
        verify(streams, never()).sendResult(any(), any());
    }

    @Test
    void unknownAnalysisIsNoOp() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.failFromDlq(id, "boom");

        verify(repository, never()).save(any());
        verify(backpressure, never()).release();
    }

    private Analysis analysis(AnalysisStatus status) {
        return Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO).status(status).build();
    }
}
