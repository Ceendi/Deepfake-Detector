package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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

import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;

/**
 * Service-layer IDOR guard (pure unit, no Spring): a resource owned by another user is
 * indistinguishable from a missing one — both surface as 404, never 403.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceIdorTest {

    @Mock
    AnalysisRepository repository;
    @Mock
    RabbitTemplate rabbitTemplate;
    @Mock
    StringRedisTemplate redis;
    @InjectMocks
    AnalysisService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void getReturnsResponseForOwner() {
        Analysis a = Analysis.builder().id(id).userId("alice").type(AnalysisType.VIDEO).build();
        when(repository.findById(id)).thenReturn(Optional.of(a));

        AnalysisResponse response = service.get(id, "alice");

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.userId()).isEqualTo("alice");
    }

    @Test
    void getRejectsCrossUserAccessAs404() {
        Analysis a = Analysis.builder().id(id).userId("alice").build();
        when(repository.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.get(id, "bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void getMissingReturns404() {
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }
}
