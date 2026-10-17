package com.deepfake.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Full-context smoke test: the whole Spring context wires up over real Postgres/Redis/RabbitMQ
 * (Testcontainers), with the JWT decoder mocked (no Keycloak) and Eureka disabled. Needs Docker.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.data.redis.password="
})
@Testcontainers
@AutoConfigureMetrics
@AutoConfigureMockMvc
class OrchestratorApplicationTests {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.0-alpine")).withExposedPorts(6379);
    @Container
    static final GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.1-alpine"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "test")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "test")
                    .withExposedPorts(5672)
                    // Port-wait alone is not enough: Docker Desktop accepts TCP on the mapped port
                    // before the broker is up, so early AMQP handshakes fail (flaky on Windows).
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    // Replaces the issuer-uri JwtDecoder so the context doesn't fetch Keycloak's OIDC metadata at startup.
    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    void openApiDocumentsBackpressureBodyAndRetryAfter() throws Exception {
        var json = mvc.perform(get("/v3/api-docs")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = new JsonMapper().readTree(json);
        var response = document.at("/paths/~1api~1analysis/post/responses/429");
        assertThat(response.at("/content/application~1json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/BackpressureResponse");
        assertThat(response.at("/headers/Retry-After/schema/type").asText()).isEqualTo("integer");
        var schema = document.at("/components/schemas/BackpressureResponse");
        assertThat(schema.path("required").toString())
                .contains("reason", "limit", "queuePosition", "retryAfterSeconds");
        assertThat(schema.at("/properties/reason/enum").toString()).contains("GLOBAL_LIMIT", "USER_LIMIT");
        assertThat(schema.at("/properties/limit/type").asText()).isEqualTo("integer");
    }

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
    void prometheusExportsAnalysisDurationBuckets() {
        var duration = prometheus.timer("analysis.duration", "type", "video");
        duration.record(Duration.ofMinutes(1));
        assertThat(prometheus.scrape()).contains("analysis_duration_seconds_bucket{");
        assertThat(duration.takeSnapshot().histogramCounts())
                .filteredOn(bucket -> Double.isFinite(bucket.bucket()))
                .anySatisfy(bucket -> assertThat(bucket.bucket()).isGreaterThan(Duration.ofMinutes(1).toNanos()));
    }

    @Test
    void contextLoads() {
    }
}
