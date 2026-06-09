package com.deepfake.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Full-context smoke test: the reactive gateway wires up over real Redis (rate limiter), with the
 * reactive JWT decoder mocked (no Keycloak) and Eureka disabled. Needs Docker.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.data.redis.password="
})
@Testcontainers
class GatewayApplicationTests {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // Replaces the issuer-uri decoder so the context doesn't fetch Keycloak's OIDC metadata at startup.
    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
    }
}
