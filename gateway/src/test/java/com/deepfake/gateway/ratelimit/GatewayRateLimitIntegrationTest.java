package com.deepfake.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Exercises real Redis quotas and outages while allowed requests reach a stub backend. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@AutoConfigureMetrics
@Testcontainers
class GatewayRateLimitIntegrationTest {

    static final DisposableServer backend = HttpServer.create().port(0)
            .handle((request, response) -> response.sendString(Mono.just("backend"))).bindNow();

    @AfterAll
    static void closeBackend() {
        backend.disposeNow();
    }

    @Container
    static final GenericContainer<?> redis = redisContainer();

    private static GenericContainer<?> redisContainer() {
        var container = new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);
        try (var socket = new ServerSocket(0)) {
            // Stop/start must keep the gateway's Redis address, rather than allocate a new port.
            container.setPortBindings(List.of(socket.getLocalPort() + ":6379"));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return container;
    }

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
        static BeanPostProcessor stubBackendRoutes() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof GatewayProperties properties) {
                        properties.getRoutes().forEach(route -> {
                            route.setUri(URI.create("http://localhost:" + backend.port()));
                            if (!route.getId().equals("file-service-upload")) {
                                route.getFilters().getFirst().getArgs()
                                        .put("redis-rate-limiter.burstCapacity", "2");
                            }
                        });
                    }
                    return bean;
                }
            };
        }

        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                    .subject("user-a")
                    .claim("realm_access", Map.of("roles", List.of("USER")))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
            return token -> "test-token".equals(token) ? Mono.just(jwt) : Mono.error(new BadJwtException("Invalid token"));
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired ProtectiveRedisRateLimiter limiter;
    @Autowired MeterRegistry meters;
    @Value("${local.management.port}") int managementPort;
    @Autowired LettuceConnectionFactory connectionFactory;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        awaitRedisRecovery();
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Autowired org.springframework.data.redis.core.ReactiveStringRedisTemplate template;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redisRequestRateLimiterScript")
    org.springframework.data.redis.core.script.RedisScript<List<Long>> script;

    @Test
    void warmupLoadsTheLimiterScriptIntoRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.scriptingCommands().scriptFlush();
            assertThat(connection.scriptingCommands().scriptExists(script.getSha1())).containsExactly(false);
            RateLimiterWarmup.run(template, script, Duration.ofSeconds(5)).block();
            assertThat(connection.scriptingCommands().scriptExists(script.getSha1())).containsExactly(true);
        }
    }

    @Test
    void defaultUploadBucketAllowsTenThenRejectsEleventhWithRetryAfter() {
        for (int i = 0; i < 10; i++) assertThat(upload()).isEqualTo(200);
        var rejected = client.post().uri("/api/files/upload")
                .header("Authorization", "Bearer test-token").header("Origin", "http://localhost:5173")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().value("Access-Control-Expose-Headers", value -> assertThat(value).contains("Retry-After"))
                .returnResult(Void.class).getResponseHeaders();
        // One token per second refills while the test runs, so the wait is whatever the bucket still lacks.
        long remaining = Long.parseLong(rejected.getFirst("X-RateLimit-Remaining"));
        assertThat(rejected.getFirst("Retry-After")).isEqualTo(String.valueOf(6 - remaining));
        var config = limiter.getConfig().get("file-service-upload");
        assertThat(config.getReplenishRate()).isEqualTo(1);
        assertThat(config.getRequestedTokens()).isEqualTo(6);
        assertThat(config.getBurstCapacity()).isEqualTo(60);
    }

    @Test
    void burstOnUploadRouteEventuallyReturns429() {
        int firstStatus = upload();
        assertThat(firstStatus).as("first request reaches the backend").isEqualTo(200);

        int rateLimited = 0;
        for (int i = 0; i < 20; i++) {
            if (upload() == 429) {
                rateLimited++;
            }
        }
        assertThat(rateLimited).as("burst exhausted -> later requests get 429").isGreaterThan(0);
    }

    @Test
    void pausedWarmRedisReturnsBounded503AndStillRequiresAuthenticationThenRecovers() {
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(connectionFactory.getClientConfiguration().getClientOptions().orElseThrow()
                .getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(limiter.isAllowed("file-service-upload", "outage-user").block().isAllowed()).isTrue();
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            client.post().uri("/api/files/upload").header("Authorization", "Bearer test-token")
                    .exchange().expectStatus().isEqualTo(503)
                    .expectHeader().doesNotExist("X-RateLimit-Remaining");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
            client.post().uri("/api/files/upload").exchange().expectStatus().isUnauthorized();
            client.post().uri("/api/files/upload").header("Authorization", "Bearer invalid")
                    .exchange().expectStatus().isUnauthorized();
        } finally {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
        awaitRedisRecovery();
        assertBucketsEnforced();
    }

    @Test
    void stoppedRedisAllowsReadsButRejectsUploadImmediatelyInSharedBypassWindow() {
        client.get().uri("/api/analysis/probe").header("Authorization", "Bearer test-token")
                .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("backend");
        double analysisBefore = degradedCount("orchestrator");
        double filesBefore = degradedCount("file-service");
        redis.getDockerClient().stopContainerCmd(redis.getContainerId()).withTimeout(1).exec();
        try {
            client.get().uri("/api/analysis/outage").header("Authorization", "Bearer test-token")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("backend");
            long start = System.nanoTime();
            client.get().uri("/api/files/probe/metadata").header("Authorization", "Bearer test-token")
                    .exchange().expectStatus().isOk().expectHeader().doesNotExist("X-RateLimit-Remaining")
                    .expectBody(String.class).isEqualTo("backend");
            client.get().uri("/api/analysis/probe/events").header("Authorization", "Bearer test-token")
                    .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("backend");
            client.post().uri("/api/files/upload").header("Authorization", "Bearer test-token")
                    .exchange().expectStatus().isEqualTo(503)
                    .expectHeader().doesNotExist("X-RateLimit-Remaining");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
            assertThat(degradedCount("orchestrator")).isEqualTo(analysisBefore + 2);
            assertThat(degradedCount("file-service")).isEqualTo(filesBefore + 1);
            assertThat(degradedCount("file-service-upload")).isZero();
            client.get().uri("/api/analysis/outage").exchange().expectStatus().isUnauthorized();
            client.get().uri("/api/analysis/outage").header("Authorization", "Bearer invalid")
                    .exchange().expectStatus().isUnauthorized();
            WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build()
                    .get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                    .expectBody(String.class).value(body -> assertThat(body)
                            .contains("gateway_ratelimiter_degraded_total{route=\"orchestrator\"}"));
        } finally {
            redis.getDockerClient().startContainerCmd(redis.getContainerId()).exec();
            awaitRedisRecovery();
        }
        assertBucketsEnforced();
    }

    private double degradedCount(String route) {
        var counter = meters.find("gateway.ratelimiter.degraded").tag("route", route).counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitRedisRecovery() {
        await().atMost(Duration.ofSeconds(10)).ignoreExceptionsInstanceOf(ResponseStatusException.class).untilAsserted(() -> {
            var response = limiter.isAllowed("file-service-upload", "recovery-" + UUID.randomUUID())
                    .block(Duration.ofSeconds(2));
            assertThat(response.isAllowed()).isTrue();
            assertThat(Long.parseLong(response.getHeaders().get("X-RateLimit-Remaining")))
                    .isGreaterThanOrEqualTo(0);
        });
    }

    private void assertBucketsEnforced() {
        for (String path : List.of("/api/files/upload", "/api/files/probe/metadata", "/api/analysis/probe")) {
            int rateLimited = 0;
            for (int i = 0; i < 11; i++) {
                var request = path.endsWith("upload") ? client.post().uri(path) : client.get().uri(path);
                int status = request.header("Authorization", "Bearer test-token").exchange()
                        .returnResult(Void.class).getStatus().value();
                assertThat(status).isIn(200, 429);
                if (status == 429) rateLimited++;
            }
            assertThat(rateLimited).as("recovered Redis enforces quota on %s", path).isGreaterThan(0);
        }
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
