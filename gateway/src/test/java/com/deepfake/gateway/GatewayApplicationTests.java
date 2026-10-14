package com.deepfake.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Full-context smoke test: the reactive gateway wires up over real Redis (rate limiter), with the
 * reactive JWT decoder mocked (no Keycloak) and Eureka disabled. Needs Docker.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.data.redis.password="
})
@Testcontainers
@AutoConfigureMetrics
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
