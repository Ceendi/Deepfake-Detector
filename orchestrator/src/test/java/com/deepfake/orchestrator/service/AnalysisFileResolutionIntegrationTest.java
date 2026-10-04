package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP and PostgreSQL boundaries; real file-service authorization is covered by the Docker smoke. */
@DataJpaTest(properties = {"file-service.lookup-timeout=500ms", "backpressure.max-inflight=20"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, AnalysisCreationService.class, FileMetadataClient.class, BackpressureGuard.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AnalysisFileResolutionIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    static final UUID FILE = UUID.randomUUID();
    static final AtomicInteger status = new AtomicInteger(200);
    static volatile String body;
    static volatile boolean slowHeaders;
    static volatile boolean slowBody;
    static volatile String authorization;
    static volatile String identityHeader;
    static final HttpServer server = startServer();

    static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/api/files/" + FILE + "/metadata", exchange -> {
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                identityHeader = exchange.getRequestHeaders().getFirst("X-User-ID");
                try {
                    if (slowHeaders) Thread.sleep(1500);
                    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status.get(), bytes.length);
                    if (slowBody) Thread.sleep(1500);
                    exchange.getResponseBody().write(bytes);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("file-service.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterAll static void stopServer() { server.stop(0); }

    @Autowired AnalysisCreationService facade;
    @Autowired AnalysisRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean FileMetadataClient files;
    @MockitoSpyBean BackpressureGuard guard;
    BackpressureGuard gate;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean AnalysisCache cache;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean AnalysisMetrics metrics;

    @BeforeEach void resetState() {
        repository.deleteAll();
        gate = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(guard);
        reset(files, gate, rabbit);
        status.set(200);
        body = "{\"fileId\":\"" + FILE + "\",\"objectKey\":\"canonical/alice.wav\"}";
        slowHeaders = false;
        slowBody = false;
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(files).resolve(any(), anyString());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(gate).acquire();
    }

    @Test void canonicalKeyReachesCommittedRowAndBothTasksWhileLegacyKeyIsIgnored() {
        var created = facade.create(request("bob/private.wav"), "alice", "verified-token");
        assertThat(created.fileKey()).isEqualTo("canonical/alice.wav");
        assertThat(repository.findById(created.id()).orElseThrow().getFileKey()).isEqualTo("canonical/alice.wav");
        var payload = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(rabbit, times(2)).convertAndSend(eq("analysis.exchange"), anyString(), payload.capture());
        for (Object task : payload.getAllValues()) {
            assertThat(((Map<?, ?>) task).get("file_key")).isEqualTo("canonical/alice.wav");
        }
        assertThat(authorization).isEqualTo("Bearer verified-token");
        assertThat(identityHeader).isNull();
    }

    @Test void fileIdAloneIsSufficient() {
        assertThat(facade.create(request(null), "alice", "verified-token").fileKey()).isEqualTo("canonical/alice.wav");
    }

    @Test void missingForeignAndDeletedResponseDoesNotAcquireCapacityOrPublish() {
        status.set(404);
        reject(HttpStatus.NOT_FOUND);
    }

    @Test void dependencyFailuresDoNotAcquireCapacityOrPublish() {
        for (int error : new int[]{401, 403, 500, 503, 302}) {
            status.set(error);
            reject(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test void oldOrMalformedMetadataFailsClosed() {
        for (String invalid : new String[]{"{}", "[]", "null", "broken", "{\"fileId\":\"" + FILE + "\"}",
                "{\"fileId\":\"" + FILE + "\",\"objectKey\":42}",
                "{\"fileId\":\"" + UUID.randomUUID() + "\",\"objectKey\":\"bob/key\"}",
                "{\"fileId\":\"" + FILE + "\",\"objectKey\":\" \"}"}) {
            body = invalid;
            reject(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Test void headersTimeoutDoesNotAcquireCapacityOrPublish() {
        slowHeaders = true;
        reject(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void wholeBodyTimeoutDoesNotAcquireCapacityOrPublish() {
        slowBody = true;
        reject(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void connectionRefusalFailsClosed() {
        var unavailable = new FileMetadataClient("http://127.0.0.1:1", java.time.Duration.ofMillis(150));
        assertThatThrownBy(() -> unavailable.resolve(FILE, "verified-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test void facadeRejectsAmbientTransactionBeforeMakingHttpRequest() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx ->
                facade.create(request(null), "alice", "verified-token")))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(files, rabbit);
        verify(gate, never()).acquire();
        assertThat(repository.count()).isZero();
    }

    private void reject(HttpStatus expected) {
        assertThatThrownBy(() -> facade.create(request("untrusted"), "alice", "verified-token"))
                .isInstanceOf(ResponseStatusException.class).hasFieldOrPropertyWithValue("statusCode", expected);
        assertThat(repository.count()).isZero();
        verifyNoInteractions(rabbit);
        verify(gate, never()).acquire();
    }
    private CreateAnalysisRequest request(String legacyKey) {
        return new CreateAnalysisRequest(FILE.toString(), legacyKey, AnalysisType.FULL, null);
    }
}
