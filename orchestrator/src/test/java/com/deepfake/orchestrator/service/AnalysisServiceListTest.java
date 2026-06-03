package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
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
    void listMapsOnlyTheUsersAnalysesPreservingOrder() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(repository.findAllByUserIdOrderByCreatedAtDesc("alice")).thenReturn(List.of(
                Analysis.builder().id(id1).userId("alice").build(),
                Analysis.builder().id(id2).userId("alice").build()));

        List<AnalysisResponse> result = service.list("alice");

        assertThat(result).extracting(AnalysisResponse::id).containsExactly(id1, id2);
        assertThat(result).extracting(AnalysisResponse::userId).containsOnly("alice");
    }
}
