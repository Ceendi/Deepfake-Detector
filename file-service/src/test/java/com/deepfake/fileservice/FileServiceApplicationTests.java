package com.deepfake.fileservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-context smoke test: the whole Spring context wires up over real Postgres (Flyway), with the
 * JWT decoder mocked (no Keycloak), Eureka disabled and dummy S3 creds (the client connects lazily).
 * Needs Docker.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "storage.access-key=test",
        "storage.secret-key=test"
})
@Testcontainers
class FileServiceApplicationTests {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Replaces the issuer-uri JwtDecoder so the context doesn't fetch Keycloak's OIDC metadata at startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
    }
}
