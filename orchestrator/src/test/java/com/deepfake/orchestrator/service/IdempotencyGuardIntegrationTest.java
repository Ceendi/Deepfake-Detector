package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Against real Redis: a (analysis, source) is unseen until marked, and the key is kept per-source. */
@Testcontainers
class IdempotencyGuardIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);

    IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory cf =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        cf.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(cf);
        template.afterPropertiesSet();
        guard = new IdempotencyGuard(template, 900);
    }

    @Test
    void unseenUntilMarked() {
        UUID id = UUID.randomUUID();

        assertThat(guard.alreadyProcessed(id, "video")).isFalse();
        guard.markProcessed(id, "video");
        assertThat(guard.alreadyProcessed(id, "video")).isTrue();
    }

    @Test
    void keyIsPerSource() {
        UUID id = UUID.randomUUID();

        guard.markProcessed(id, "video");

        // FULL produces two results under one id — marking video must not mask audio.
        assertThat(guard.alreadyProcessed(id, "audio")).isFalse();
    }
}
