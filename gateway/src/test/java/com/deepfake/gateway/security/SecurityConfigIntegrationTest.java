package com.deepfake.gateway.security;

import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Mono;

/**
 * Checks both listening ports with real Redis and a locally verified test identity.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "eureka.client.enabled=false", "spring.data.redis.password="
})
@Testcontainers
@AutoConfigureMetrics
class SecurityConfigIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    WebTestClient webTestClient;
    WebTestClient managementClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
        managementClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + managementPort).build();
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none").subject("user-a")
                .claim("realm_access", Map.of("roles", List.of("USER")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(jwtDecoder.decode("test-token")).thenReturn(Mono.just(jwt));
    }

    @Test
    void protectedRouteWithoutTokenReturns401() {
        webTestClient.get().uri("/api/analysis")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void actuatorIsUnavailableOnPublicPortEvenWithAUserToken() {
        for (String path : List.of("/actuator/health", "/actuator/health/redis",
                "/actuator/info", "/actuator/prometheus")) {
            webTestClient.get().uri(path).exchange().expectStatus().isUnauthorized();
            webTestClient.get().uri(path).header("Authorization", "Bearer test-token")
                    .exchange().expectStatus().isForbidden();
            webTestClient.get().uri(path)
                    .header("X-Forwarded-Port", String.valueOf(managementPort))
                    .header("Forwarded", "host=localhost:" + managementPort)
                    .exchange().expectStatus().isUnauthorized();
        }
    }

    @Test
    void anonymousInternalHealthDoesNotRevealComponentsOrDetails() {
        managementClient.get().uri("/actuator/health")
                .exchange().expectStatus().isOk()
                .expectBody().json("{\"status\":\"UP\"}")
                .jsonPath("$.components").doesNotExist()
                .jsonPath("$.details").doesNotExist();
    }

    @Test
    void anonymousInternalPrometheusRemainsScrapable() {
        managementClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body ->
                        org.assertj.core.api.Assertions.assertThat(body).contains("jvm_memory_used_bytes"));
    }

    @Test
    void internalPortDoesNotBypassApiAuthentication() {
        managementClient.get().uri("/api/analysis").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymousInternalComponentHealthAlsoHidesDetails() {
        managementClient.get().uri("/actuator/health/redis").exchange().expectStatus().isNotFound();
    }
}
