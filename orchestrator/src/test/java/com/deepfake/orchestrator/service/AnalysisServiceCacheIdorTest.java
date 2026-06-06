package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/**
 * Security regression with real Redis: the IDOR guard runs after the cache read, so once user A's
 * GET populates the cache, user B's GET of the same id is a cache hit yet still 404s — the guard is
 * not bypassed. The cache hit also proves the second read skips the DB.
 */
@Testcontainers
class AnalysisServiceCacheIdorTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);

    AnalysisRepository repository;
    AnalysisService service;
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        cf.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(cf);
        template.afterPropertiesSet();

        repository = mock(AnalysisRepository.class);
        service = new AnalysisService(repository, mock(RabbitTemplate.class), template,
                new AnalysisCache(template), mock(AnalysisStreamRegistry.class));
    }

    @Test
    void cacheHitStillEnforcesIdorAndSkipsSecondDbRead() {
        Analysis owned = Analysis.builder().id(id).userId("user-a").fileId("f").fileKey("k")
                .type(AnalysisType.VIDEO).status(AnalysisStatus.PENDING).build();
        when(repository.findById(id)).thenReturn(Optional.of(owned));

        AnalysisResponse first = service.get(id, "user-a"); // miss -> DB -> populate cache
        assertThat(first.userId()).isEqualTo("user-a");

        AnalysisResponse second = service.get(id, "user-a"); // hit
        assertThat(second.id()).isEqualTo(id);
        verify(repository, times(1)).findById(id); // two GETs, one DB read

        assertThatThrownBy(() -> service.get(id, "user-b")) // hit, but foreign owner
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        verify(repository, times(1)).findById(id); // guard hit the cache, not the DB
    }
}
