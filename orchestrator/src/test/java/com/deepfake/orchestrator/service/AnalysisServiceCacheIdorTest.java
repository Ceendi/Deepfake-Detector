package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
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
            new GenericContainer<>(DockerImageName.parse("redis:8.10.2-alpine")).withExposedPorts(6379);

    LettuceConnectionFactory connectionFactory;
    AnalysisRepository repository;
    AnalysisService service;
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        connectionFactory = cf;
        cf.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(cf);
        template.afterPropertiesSet();

        repository = mock(AnalysisRepository.class);
        service = new AnalysisService(repository, mock(com.deepfake.orchestrator.repository.AnalysisTaskOutboxRepository.class), template,
                new OptionalRedisOperations(Duration.ofSeconds(2)),
                new AnalysisCache(template, new OptionalRedisOperations(Duration.ofSeconds(2)), true), mock(AnalysisStreamRegistry.class),
                mock(BackpressureGuard.class), mock(IdempotencyGuard.class), mock(AnalysisMetrics.class), mock(ArtifactCleanupStore.class));
    }

    @org.junit.jupiter.api.AfterEach
    void closeRedisConnection() {
        connectionFactory.destroy();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void getAlreadyReadingCannotRefillAfterTerminalOrDelete(boolean delete) throws Exception {
        Analysis old = Analysis.builder().id(id).userId("user-a").fileId("f").fileKey("k")
                .type(AnalysisType.VIDEO).status(delete ? AnalysisStatus.COMPLETED : AnalysisStatus.PENDING).build();
        var current = new java.util.concurrent.atomic.AtomicReference<>(old);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var read = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(repository.findById(id)).thenAnswer(invocation -> {
            Analysis snapshot = current.get();
            if (first.getAndSet(false)) {
                read.countDown();
                assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
            return Optional.ofNullable(snapshot);
        });
        if (delete) {
            when(repository.deleteTerminal(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.eq("user-a"),
                    org.mockito.ArgumentMatchers.any())).thenReturn(1);
        } else {
            when(repository.cancelIfActive(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
                current.set(Analysis.builder().id(id).userId("user-a").fileId("f").fileKey("k")
                        .type(AnalysisType.VIDEO).status(AnalysisStatus.CANCELLED).build());
                return 1;
            });
        }
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reading = pool.submit(() -> service.get(id, "user-a"));
            try {
                assertThat(read.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                if (delete) {
                    service.delete(id, "user-a");
                    current.set(null);
                } else {
                    service.cancel(id, "user-a");
                }
            } finally {
                release.countDown();
            }
            reading.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        if (delete) {
            assertThatThrownBy(() -> service.get(id, "user-a"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        } else {
            assertThat(service.get(id, "user-a").status()).isEqualTo(AnalysisStatus.CANCELLED);
        }
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
