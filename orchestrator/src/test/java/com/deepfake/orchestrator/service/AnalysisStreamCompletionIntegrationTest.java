package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** Real PostgreSQL, real service proxies and registry; test transactions do not hide commits. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, AnalysisStreamCompletionIntegrationTest.RegistryConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AnalysisStreamCompletionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired ControlledRegistry streams;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AnalysisCache cache;
    @MockitoBean BackpressureGuard capacity;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisMetrics metrics;
    UUID id;

    @BeforeEach
    void seed() {
        streams.beforeRegister = () -> {};
        streams.afterRegister = () -> {};
        streams.emitters.clear();
        repository.deleteAll();
        id = repository.saveAndFlush(Analysis.builder().userId("alice").fileId("file")
                .fileKey("key").type(AnalysisType.VIDEO).build()).getId();
    }

    @Test
    void completionBeforeFirstReadIsReplayed() {
        finish();
        assertResult((RecordingEmitter) service.openStream(id, "alice"));
    }

    @Test
    void completionExactlyBetweenReadAndRegistrationIsReplayed() {
        streams.beforeRegister = this::finishIndependently;
        assertResult((RecordingEmitter) service.openStream(id, "alice"));
    }

    @Test
    void completionAfterRegistrationBeforeFreshReadIsDelivered() {
        streams.afterRegister = this::finishIndependently;
        assertResult((RecordingEmitter) service.openStream(id, "alice"));
    }

    @Test
    void completionAfterBothReadsIsPushedToBothSubscribersAndLateSubscriber() {
        var first = (RecordingEmitter) service.openStream(id, "alice");
        var second = (RecordingEmitter) service.openStream(id, "alice");
        assertThat(first.results).isEmpty();
        assertThat(second.results).isEmpty();
        finish();
        assertResult(first);
        assertResult(second);
        var late = (RecordingEmitter) service.openStream(id, "alice");
        assertResult(late);
        assertThat(first.results).hasSize(1);
        assertThat(second.results).hasSize(1);
    }

    @Test
    void scalarSnapshotIgnoresStaleManagedEntityAndRepeatableReadCaller() {
        var outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(tx -> {
            Analysis stale = repository.findById(id).orElseThrow();
            streams.beforeRegister = this::finishIndependently;
            assertResult((RecordingEmitter) service.openStream(id, "alice"));
            assertThat(repository.findById(id).orElseThrow()).isSameAs(stale);
            assertThat(stale.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uncommittedTerminalStateCannotLeakAndRollbackLeavesStreamActive(boolean rollback) {
        var tx = new TransactionTemplate(transactionManager);
        RecordingEmitter emitter = tx.execute(status -> {
            finish(); // Joins this transaction; the afterCommit push has not run.
            var subscription = (RecordingEmitter) service.openStream(id, "alice");
            assertThat(subscription.results).isEmpty();
            assertThat(subscription.closed).isFalse();
            if (rollback) status.setRollbackOnly();
            return subscription;
        });
        if (rollback) {
            assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(emitter.results).isEmpty();
            assertThat(emitter.closed).isFalse();
            finish();
        }
        assertResult(emitter);
    }

    @Test
    void foreignUserReceivesNeitherSnapshotNorEvents() {
        assertThat(repository.streamSnapshot(id, "bob")).isEmpty();
        assertThatThrownBy(() -> service.openStream(id, "bob"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(streams.emitters).isEmpty();
        finish();
        assertThatThrownBy(() -> service.openStream(id, "bob"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(streams.emitters).isEmpty();
    }

    @Test
    void failedFreshReadDiscardsOnlyNewEmitter() {
        streams.afterRegister = () -> repository.deleteById(id);
        assertThatThrownBy(() -> service.openStream(id, "alice"))
                .isInstanceOf(ResponseStatusException.class);
        var emitter = streams.emitters.getFirst();
        assertThat(emitter.closed).isTrue();
        assertThat(emitter.results).isEmpty();
        streams.sendResult(id, new AnalysisResultEvent(id.toString(), "COMPLETED", "FAKE", BigDecimal.ONE));
        assertThat(emitter.results).isEmpty();
    }

    private void finishIndependently() {
        try (var pool = Executors.newSingleThreadExecutor()) {
            pool.submit(this::finish).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void finish() {
        service.handleResult(Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                "result", Map.of("prob_fake", "0.8")));
    }

    private void assertResult(RecordingEmitter emitter) {
        assertThat(emitter.results).hasSize(1);
        assertThat(emitter.results.getFirst().status()).isEqualTo("COMPLETED");
        assertThat(emitter.results.getFirst().verdict()).isEqualTo("FAKE");
        assertThat(emitter.results.getFirst().confidence()).isEqualByComparingTo("0.6");
        assertThat(emitter.closed).isTrue();
    }

    @TestConfiguration
    static class RegistryConfig {
        @Bean ControlledRegistry streams() { return new ControlledRegistry(); }
    }

    static class ControlledRegistry extends AnalysisStreamRegistry {
        Runnable beforeRegister = () -> {};
        Runnable afterRegister = () -> {};
        final List<RecordingEmitter> emitters = new ArrayList<>();
        @Override public SseEmitter register(UUID id) {
            beforeRegister.run();
            SseEmitter emitter = super.register(id);
            afterRegister.run();
            return emitter;
        }
        @Override protected SseEmitter createEmitter() {
            var emitter = new RecordingEmitter();
            emitters.add(emitter);
            return emitter;
        }
    }

    static class RecordingEmitter extends SseEmitter {
        final List<AnalysisResultEvent> results = new ArrayList<>();
        boolean closed;
        @Override public void send(SseEventBuilder builder) throws IOException {
            if (closed) throw new IllegalStateException("Emitter closed");
            builder.build().forEach(data -> {
                if (data.getData() instanceof AnalysisResultEvent result) results.add(result);
            });
        }
        @Override public void complete() { closed = true; super.complete(); }
    }
}
