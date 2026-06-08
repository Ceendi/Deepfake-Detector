package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * openStream channel authorization (pure unit): only the owner registers an emitter (foreign/missing
 * → 404, like get()); an already-terminal analysis is pushed its result and closed at open time.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceStreamTest {

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
    @InjectMocks
    AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void ownerOpensStreamForActiveAnalysis() {
        Analysis a = Analysis.builder().id(id).userId("alice")
                .status(AnalysisStatus.PROCESSING).type(AnalysisType.VIDEO).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
        SseEmitter emitter = mock(SseEmitter.class);
        when(streams.register(id)).thenReturn(emitter);

        SseEmitter result = service.openStream(id, "alice");

        assertThat(result).isSameAs(emitter);
        verify(streams).register(id);
        verify(streams, never()).sendResult(any(), any());
        verify(streams, never()).complete(any());
    }

    @Test
    void foreignUserGets404AndNoStream() {
        Analysis a = Analysis.builder().id(id).userId("alice").status(AnalysisStatus.PROCESSING).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.openStream(id, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        verifyNoInteractions(streams);
    }

    @Test
    void missingAnalysisGets404() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openStream(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        verifyNoInteractions(streams);
    }

    @Test
    void alreadyTerminalAnalysisPushesResultAndCloses() {
        Analysis a = Analysis.builder().id(id).userId("alice").status(AnalysisStatus.COMPLETED)
                .verdict("FAKE").confidence(new BigDecimal("0.8")).type(AnalysisType.VIDEO).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));
        when(streams.register(id)).thenReturn(mock(SseEmitter.class));

        service.openStream(id, "alice");

        verify(streams).sendResult(eq(id), any(AnalysisResultEvent.class));
        verify(streams).complete(id);
    }
}
