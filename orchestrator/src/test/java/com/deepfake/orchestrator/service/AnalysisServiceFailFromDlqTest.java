package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
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
    @Mock AnalysisMetrics metrics;
    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void activeAnalysisFailsAndReleasesOnce() {
        when(repository.failIfActive(eq(id), eq(AnalysisStatus.FAILED), any(), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));

        service.failFromDlq(id, "boom");

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(repository).failIfActive(eq(id), eq(AnalysisStatus.FAILED), msg.capture(), any(), any());
        assertThat(msg.getValue()).isEqualTo("dead-letter: boom");
        verify(backpressure).release();
        verify(streams).complete(id);
    }

    @Test
    void failStuckUsesStuckMessage() {
        when(repository.failIfActive(eq(id), eq(AnalysisStatus.FAILED), any(), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(analysis(AnalysisStatus.FAILED)));

        service.failStuck(id, 600);

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(repository).failIfActive(eq(id), eq(AnalysisStatus.FAILED), msg.capture(), any(), any());
        assertThat(msg.getValue()).contains("stuck > 600s");
        verify(backpressure).release();
    }

    // CAS returns 0 for both already-terminal and unknown ids (the mock default), so the transition
    // is a no-op without re-reading or releasing.
    @Test
    void nonActiveAnalysisIsNoOp() {
        service.failFromDlq(id, "boom");

        verify(repository).failIfActive(eq(id), eq(AnalysisStatus.FAILED), any(), any(), any());
        verify(repository, never()).findById(any());
        verify(backpressure, never()).release();
        verify(streams, never()).sendResult(any(), any());
    }

    private Analysis analysis(AnalysisStatus status) {
        return Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO).status(status).build();
    }
}
