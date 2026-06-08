package com.deepfake.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Mono;

/**
 * Runtime check of the per-user Redis token bucket: the upload route allows a burst of 10, so a
 * rapid 11th request from the same user is rejected with 429. Redis is real (Testcontainers); the
 * JWT decoder is stubbed so no Keycloak is needed, and Eureka is off (allowed requests just fail to
 * route, which is irrelevant — we only assert that the limiter starts returning 429).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayRateLimitIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("eureka.client.enabled", () -> "false");
    }

    @TestConfiguration
    static class StubJwtConfig {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                    .subject("user-a")
                    .claim("realm_access", Map.of("roles", List.of("USER")))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
            return token -> Mono.just(jwt);
        }
    }

    @Value("${local.server.port}")
    int port;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void burstOnUploadRouteEventuallyReturns429() {
        int firstStatus = upload();
        assertThat(firstStatus).as("first request consumes a token, not rate-limited").isNotEqualTo(429);

        int rateLimited = 0;
        for (int i = 0; i < 20; i++) {
            if (upload() == 429) {
                rateLimited++;
            }
        }
        assertThat(rateLimited).as("burst of 10 exhausted -> later requests get 429").isGreaterThan(0);
    }

    private int upload() {
        return client.post().uri("/api/files/upload")
                .header("Authorization", "Bearer test-token")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
    }
}
