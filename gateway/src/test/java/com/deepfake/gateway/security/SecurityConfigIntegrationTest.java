package com.deepfake.gateway.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies the edge security wiring: protected routes reject anonymous requests at the
 * Gateway, permitAll actuator paths stay open. No Keycloak needed — anonymous requests
 * are rejected before any JWT decode happens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigIntegrationTest {

    @Value("${local.server.port}")
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void protectedRouteWithoutTokenReturns401() {
        webTestClient.get().uri("/api/analysis")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void actuatorInfoIsPublic() {
        webTestClient.get().uri("/actuator/info")
                .exchange()
                .expectStatus().isOk();
    }
}
