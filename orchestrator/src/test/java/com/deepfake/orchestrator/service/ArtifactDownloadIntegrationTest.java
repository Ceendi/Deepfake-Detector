package com.deepfake.orchestrator.service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import com.deepfake.orchestrator.config.ObjectStorageConfig;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Real PostgreSQL proxies and production S3 configuration against an HTTP fault fixture. */
@DataJpaTest(showSql = false, properties = {"spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ArtifactService.class, ArtifactAuthorizationService.class, ObjectStorageConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ArtifactDownloadIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    private static final int LIMIT = 8388608;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final ExecutorService handlers = Executors.newFixedThreadPool(8);
    private static final HttpServer server = startServer();
    private static final AtomicReference<Mode> mode = new AtomicReference<>(Mode.HEALTHY);
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicInteger activeHandlers = new AtomicInteger();
    private static volatile CountDownLatch entered = new CountDownLatch(0);
    private static volatile CountDownLatch release = new CountDownLatch(0);
    @Autowired ArtifactService service;
    @Autowired AnalysisRepository repository;
    @Autowired HikariDataSource dataSource;
    @Autowired S3Client s3;
    UUID id;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("storage.endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("storage.access-key", () -> "test");
        registry.add("storage.secret-key", () -> "test");
    }

    @BeforeEach
    void seed() {
        mode.set(Mode.HEALTHY);
        requests.set(0);
        repository.deleteAll();
        var analysis = Analysis.builder().userId("alice").fileId("f").fileKey("k").type(AnalysisType.AUDIO)
                .audioDetails(Map.of("gradcamKeys", List.of("attempt/audio/gradcam.png"))).build();
        id = repository.saveAndFlush(analysis).getId();
    }

    @AfterEach
    void releaseHandlers() {
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> activeHandlers.get() == 0);
    }

    @AfterAll
    static void closeServer() { server.stop(0); handlers.shutdownNow(); }

    @Test
    void concurrentBlockedRemoteReadsReleaseAllDatabaseConnectionsBeforeBodyCompletes() throws Exception {
        assertThat(s3.serviceClientConfiguration().overrideConfiguration().apiCallTimeout()).contains(Duration.ofSeconds(5));
        assertThat(s3.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
        entered = new CountDownLatch(3);
        release = new CountDownLatch(1);
        mode.set(Mode.BLOCKED_BODY);
        try (ExecutorService downloads = Executors.newFixedThreadPool(3)) {
            var futures = List.of(downloads.submit(this::download), downloads.submit(this::download), downloads.submit(this::download));
            try {
                assertThat(entered.await(1500, TimeUnit.MILLISECONDS)).as("three remote reads despite a two-connection DB pool").isTrue();
                await().atMost(Duration.ofMillis(500)).until(() -> dataSource.getHikariPoolMXBean().getActiveConnections() == 0);
                long start = System.nanoTime();
                assertThat(repository.count()).isEqualTo(1);
                assertThat(elapsed(start)).isLessThan(Duration.ofMillis(500));
            } finally { release.countDown(); }
            for (var future : futures) assertThat(future.get(3, TimeUnit.SECONDS)).isEqualTo(PNG);
        }
    }

    @Test
    void ownerAndRecordedKeyChecksRejectBeforeAnyStorageRequest() {
        assert404(() -> service.download(id, "audio", "gradcam.png", "mallory"));
        assert404(() -> service.download(id, "audio", "unknown.png", "alice"));
        assertThat(requests).hasValue(0);
        assertThat(download()).isEqualTo(PNG);
    }

    @Test
    void missingObjectRemains404() {
        mode.set(Mode.MISSING);
        assert404(this::download);
    }

    @Test
    void stalledHeadersAndTricklingBodyBothRespectFullOperationBudgetAndClientRecovers() {
        for (Mode fault : List.of(Mode.STALLED_HEADERS, Mode.TRICKLE)) {
            mode.set(fault);
            long start = System.nanoTime();
            assert503(this::download);
            assertThat(elapsed(start)).as("%s includes body consumption under production 5s/2s budgets", fault)
                    .isLessThan(Duration.ofSeconds(6));
            mode.set(Mode.HEALTHY);
            assertThat(download()).as("client/connection resources remain usable after %s", fault).isEqualTo(PNG);
        }
    }

    @Test
    void oversizedDeclaredAndChunkedBodiesAreRejectedWithoutBreakingLaterReads() {
        for (Mode fault : List.of(Mode.OVERSIZED_HEADER, Mode.OVERSIZED_CHUNKED)) {
            mode.set(fault);
            assert503(this::download);
            mode.set(Mode.HEALTHY);
            assertThat(download()).isEqualTo(PNG);
        }
    }

    private byte[] download() { return service.download(id, "audio", "gradcam.png", "alice"); }
    private static Duration elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start); }
    private void assert404(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) { assertStatus(call, HttpStatus.NOT_FOUND); }
    private void assert503(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) { assertStatus(call, HttpStatus.SERVICE_UNAVAILABLE); }
    private void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(status));
    }

    private static HttpServer startServer() {
        try {
            HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fixture.createContext("/", ArtifactDownloadIntegrationTest::respond);
            fixture.setExecutor(handlers);
            fixture.start();
            return fixture;
        } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    }

    private static void respond(HttpExchange exchange) {
        Mode scenario = mode.get();
        requests.incrementAndGet();
        activeHandlers.incrementAndGet();
        try {
            if (scenario == Mode.STALLED_HEADERS) Thread.sleep(3000);
            if (scenario == Mode.MISSING) {
                byte[] body = "<Error><Code>NoSuchKey</Code><Message>Missing</Message></Error>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
            } else if (scenario == Mode.OVERSIZED_HEADER) {
                exchange.sendResponseHeaders(200, LIMIT + 1L);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                Thread.sleep(100); // make the oversized header observable before closing the fixture
            } else if (scenario == Mode.OVERSIZED_CHUNKED) {
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[8192];
                for (int written = 0; written <= LIMIT; written += chunk.length) exchange.getResponseBody().write(chunk);
            } else if (scenario == Mode.TRICKLE) {
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 100; i++) {
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                    Thread.sleep(100); // always faster than socket idle timeout; only an overall/attempt deadline stops it
                }
            } else {
                exchange.sendResponseHeaders(200, PNG.length);
                if (scenario == Mode.BLOCKED_BODY) {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                exchange.getResponseBody().write(PNG);
            }
        } catch (IOException ignored) {
            // Expected when the production client aborts an incomplete/oversized/timed-out response.
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        finally { exchange.close(); activeHandlers.decrementAndGet(); }
    }

    private enum Mode { HEALTHY, BLOCKED_BODY, MISSING, STALLED_HEADERS, TRICKLE, OVERSIZED_HEADER, OVERSIZED_CHUNKED }
}
