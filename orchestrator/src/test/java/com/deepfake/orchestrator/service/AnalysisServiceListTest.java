package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.repository.AnalysisTaskOutboxRepository;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceListTest {

    @Mock
    AnalysisRepository repository;
    @Mock
    AnalysisTaskOutboxRepository outbox;
    @Mock
    StringRedisTemplate redis;
    @Mock
    AnalysisCache cache;
    @Spy
    OptionalRedisOperations optionalRedis =
            new OptionalRedisOperations(Duration.ofSeconds(2));

    @Mock ArtifactCleanupStore artifactCleanup;

    @InjectMocks
    AnalysisService service;

    @Test
    void offsetBeyondJpaIntegerRangeIsRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.list("alice", PageRequest.of(Integer.MAX_VALUE, 20)))
                .isInstanceOf(org.springframework.beans.TypeMismatchException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void largestJpaOffsetIsAccepted() {
        Pageable pageable = PageRequest.of(Integer.MAX_VALUE, 1);
        Pageable expected = PageRequest.of(Integer.MAX_VALUE, 1, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(repository.findByUserId("alice", expected)).thenReturn(Page.empty(expected));
        assertThat(service.list("alice", pageable)).isEmpty();
        verify(repository).findByUserId("alice", expected);
    }

    @Test
    void listDelegatesToRepositoryScopedToTheCurrentUser() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "unknown"));
        Pageable expected = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<AnalysisSummary> page = new PageImpl<>(List.of(), pageable, 0);
        when(repository.findByUserId("alice", expected)).thenReturn(page);

        Page<AnalysisSummary> result = service.list("alice", pageable);

        assertThat(result).isSameAs(page);
        verify(repository).findByUserId("alice", expected);
    }
}
