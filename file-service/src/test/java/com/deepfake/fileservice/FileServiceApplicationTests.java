package com.deepfake.fileservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

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
@AutoConfigureMetrics
class FileServiceApplicationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine3.24");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Replaces the issuer-uri JwtDecoder so the context doesn't fetch Keycloak's OIDC metadata at startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired PrometheusMeterRegistry prometheus;

    @Test
    void prometheusExportsHttpLatencyBuckets() {
        var latency = prometheus.timer("http.server.requests", "method", "GET", "uri", "/api/test",
                "status", "200", "outcome", "SUCCESS", "exception", "none");
        latency.record(Duration.ofMinutes(1));
        assertThat(prometheus.scrape()).contains("http_server_requests_seconds_bucket{");
        assertThat(latency.takeSnapshot().histogramCounts())
                .filteredOn(bucket -> Double.isFinite(bucket.bucket()))
                .anySatisfy(bucket -> assertThat(bucket.bucket()).isGreaterThan(Duration.ofMinutes(1).toNanos()));
    }

    @Test
    void contextLoads() {
    }
}
