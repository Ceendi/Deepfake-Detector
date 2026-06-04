package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.repository.AnalysisRepository;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceListTest {

    @Mock
    AnalysisRepository repository;
    @Mock
    RabbitTemplate rabbitTemplate;
    @Mock
    StringRedisTemplate redis;
    @InjectMocks
    AnalysisService service;

    @Test
    void listDelegatesToRepositoryScopedToTheCurrentUser() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AnalysisSummary> page = new PageImpl<>(List.of(), pageable, 0);
        when(repository.findByUserId("alice", pageable)).thenReturn(page);

        Page<AnalysisSummary> result = service.list("alice", pageable);

        assertThat(result).isSameAs(page);
        verify(repository).findByUserId("alice", pageable);
    }
}
