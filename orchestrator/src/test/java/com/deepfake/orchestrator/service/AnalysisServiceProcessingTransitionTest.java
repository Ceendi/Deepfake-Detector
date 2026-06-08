package com.deepfake.orchestrator.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redis;
    @Mock AnalysisCache cache;
    @Mock AnalysisStreamRegistry streams;
    @Mock BackpressureGuard backpressure;
    @Mock IdempotencyGuard idempotency;
    @InjectMocks AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void activeProgressEvictsAndPushes() {
        when(repository.recordProgress(eq(id), eq(AnalysisStatus.PROCESSING), any(), any(Instant.class)))
                .thenReturn(1);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        service.handleProgress(progress());

        verify(cache).evictById(id);
        verify(streams).sendProgress(eq(id), any());
    }

    @Test
    void snapshotFailureStillPushesProgress() {
        when(repository.recordProgress(eq(id), eq(AnalysisStatus.PROCESSING), any(), any(Instant.class)))
                .thenReturn(1);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doThrow(new RedisConnectionFailureException("down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        service.handleProgress(progress());

        verify(streams).sendProgress(eq(id), any()); // live push survives the snapshot write failing
    }

    @Test
    void lateProgressOnTerminalIsDropped() {
        when(repository.recordProgress(eq(id), eq(AnalysisStatus.PROCESSING), any(), any(Instant.class)))
                .thenReturn(0);

        service.handleProgress(progress());

        verify(cache, never()).evictById(any());
        verifyNoInteractions(streams);
        verify(redis, never()).opsForValue();
    }

    private Map<String, Object> progress() {
        return Map.of("analysis_id", id.toString(), "source", "video", "progress", 0, "stage", "LOADING");
    }
}
