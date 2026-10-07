package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.exception.TooManyAnalysesException;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** Independent SQL reads observe committed data; no enclosing test transaction hides rollbacks. */
@DataJpaTest(showSql = false, properties = "backpressure.max-inflight=1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, BackpressureGuard.class, IdempotencyGuard.class,
        AnalysisResultAcceptanceIntegrationTest.RedisConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AnalysisResultAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired IdempotencyGuard idempotency;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean AnalysisMetrics metrics;

    @BeforeEach
    void resetState() {
        dropCommitTrigger();
        repository.deleteAll();
        var keys = redis.keys("dedup:*"); // Only this class's disposable Redis instance.
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        clearInvocations(streams, metrics, rabbit, cache);
    }

    @AfterEach
    void dropCommitTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_result_commit ON analysis");
    }

    @Test
    void fullKeepsFirstVideoAfterMarkerLossAndSuccessOrFailureDuplicates() {
        UUID id = seed(AnalysisType.FULL);
        service.handleResult(completed(id, "video", "0.8", "first"));
        Map<String, Object> partial = stored(id);
        assertThat(partial.get("status")).isEqualTo("PENDING");
        assertThat(idempotency.alreadyProcessed(id, "video")).isTrue();
        assertCapacityOccupied();

        for (Map<String, Object> duplicate : List.of(completed(id, "video", "0.1", "duplicate"),
                failed(id, "video"))) {
            idempotency.clear(id);
            assertThat(idempotency.alreadyProcessed(id, "video")).isFalse();
            service.handleResult(duplicate);
            assertThat(stored(id)).isEqualTo(partial); // Includes JSONB and updated_at.
            assertThat(idempotency.alreadyProcessed(id, "video")).isFalse();
            assertCapacityOccupied();
        }
        verifyNoInteractions(streams, metrics);

        service.handleResult(completed(id, "audio", "0.4", "audio"));
        assertFull(id, "0.8", "0.4", "FAKE", "0.28");
        assertThat(stored(id).get("video_details")).isEqualTo(partial.get("video_details"));
        verify(streams, times(1)).sendResult(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any());
        create(); // Exactly one place became available.
        assertCapacityOccupied();
    }

    static Stream<Arguments> competingResults() {
        return Stream.of("video", "audio").flatMap(source -> Stream.of(false, true)
                .flatMap(failure -> Stream.of(false, true)
                        .map(rollback -> Arguments.of(source, failure, rollback))));
    }

    @ParameterizedTest(name = "{0}, competing failure={1}, first rollback={2}")
    @MethodSource("competingResults")
    void rowLockProtectsAcceptanceUntilCommitOrRollback(String source, boolean failure,
                                                        boolean rollback) throws Exception {
        UUID id = seed(AnalysisType.FULL);
        Map<String, Object> before = stored(id);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(tx -> {
                        service.handleResult(completed(id, source, "0.8", "first"));
                        written.countDown();
                        awaitLatch(finish);
                        if (rollback) tx.setRollbackOnly();
                    }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(stored(id)).isEqualTo(before);
                assertThat(idempotency.alreadyProcessed(id, source)).isFalse();
                var second = pool.submit(() -> service.handleResult(failure
                        ? failed(id, source) : completed(id, source, "0.1", "second")));
                // Prove the second delivery reached PostgreSQL and is waiting on the first tx.
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                        SELECT COUNT(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                        """, Long.class) > 0);
                assertThat(second.isDone()).isFalse();
                assertThat(stored(id)).isEqualTo(before);
                finish.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        Map<String, Object> accepted = stored(id);
        if (rollback && failure) {
            assertThat(accepted.get("status")).isEqualTo("FAILED");
            assertThat(accepted.get(source + "_prob")).isNull();
            assertThat(accepted.get(source + "_details")).isNull();
            assertThat(accepted.get("error_message")).isEqualTo("[TEST] failed delivery");
            verify(streams, times(1)).sendResult(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any());
        } else {
            String probability = rollback ? "0.1" : "0.8";
            assertThat((BigDecimal) accepted.get(source + "_prob")).isEqualByComparingTo(probability);
            assertThat(accepted.get(source + "_details").toString())
                    .contains(rollback ? "second" : "first").doesNotContain(rollback ? "first" : "second");
            assertThat(accepted.get("error_message")).isNull();
            assertCapacityOccupied();
            String sibling = source.equals("video") ? "audio" : "video";
            service.handleResult(completed(id, sibling, "0.4", "sibling"));
            BigDecimal total = new BigDecimal(probability).multiply(new BigDecimal(source.equals("video") ? "0.6" : "0.4"))
                    .add(new BigDecimal("0.4").multiply(new BigDecimal(source.equals("video") ? "0.4" : "0.6")));
            assertThat(stored(id).get("status")).isEqualTo("COMPLETED");
            assertThat((BigDecimal) stored(id).get("confidence"))
                    .isEqualByComparingTo(total.subtract(new BigDecimal("0.5")).abs().multiply(new BigDecimal("2")));
            verify(streams, times(1)).sendResult(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any());
        }
        assertThat(idempotency.alreadyProcessed(id, source)).isTrue();
        create();
        assertCapacityOccupied();
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisType.class, names = {"VIDEO", "AUDIO"})
    void legacyZeroProbabilityWithoutDetailsIsAlreadyAccepted(AnalysisType type) {
        UUID id = seed(AnalysisType.FULL);
        String source = type == AnalysisType.VIDEO ? "video" : "audio";
        // Simulate a committed legacy result without metadata or a Redis marker.
        jdbc.update("UPDATE analysis SET " + source + "_prob = 0 WHERE id = ?", id);
        Map<String, Object> accepted = stored(id);
        service.handleResult(completed(id, source, "0.9", "duplicate"));
        service.handleResult(failed(id, source));
        assertThat(stored(id)).isEqualTo(accepted);
        assertThat(idempotency.alreadyProcessed(id, source)).isFalse();
        String sibling = type == AnalysisType.VIDEO ? "audio" : "video";
        service.handleResult(completed(id, sibling, "0.4", "sibling"));
        assertFull(id, type == AnalysisType.VIDEO ? "0" : "0.4",
                type == AnalysisType.AUDIO ? "0" : "0.4", "REAL",
                type == AnalysisType.VIDEO ? "0.68" : "0.52");
        assertThat(stored(id).get(source + "_details")).isNull();
    }

    static Stream<Arguments> firstFailures() {
        return Stream.of("video", "audio").flatMap(source -> Stream.of(false, true)
                .map(rollback -> Arguments.of(source, rollback)));
    }

    @ParameterizedTest(name = "first failure: {0}, rollback={1}")
    @MethodSource("firstFailures")
    void firstFailureOwnsTheSourceUntilRollback(String source, boolean rollback) throws Exception {
        UUID id = seed(AnalysisType.FULL);
        Map<String, Object> before = stored(id);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(tx -> {
                        service.handleResult(failed(id, source));
                        written.countDown();
                        awaitLatch(finish);
                        if (rollback) tx.setRollbackOnly();
                    }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(stored(id)).isEqualTo(before);
                assertCapacityOccupied();
                var second = pool.submit(() -> service.handleResult(completed(id, source, "0.8", "retry")));
                await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                        SELECT COUNT(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                        """, Long.class) > 0);
                assertThat(second.isDone()).isFalse();
                finish.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        if (rollback) {
            assertThat(stored(id).get("status")).isEqualTo("PENDING");
            assertThat((BigDecimal) stored(id).get(source + "_prob")).isEqualByComparingTo("0.8");
            assertThat(stored(id).get("error_message")).isNull();
            verifyNoInteractions(streams);
            assertCapacityOccupied();
        } else {
            assertThat(stored(id).get("status")).isEqualTo("FAILED");
            assertThat(stored(id).get(source + "_prob")).isNull();
            assertThat(stored(id).get("error_message")).isEqualTo("[TEST] failed delivery");
            verify(streams, times(1)).sendResult(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any());
            create();
            assertCapacityOccupied();
        }
        assertThat(idempotency.alreadyProcessed(id, source)).isTrue();
    }

    static Stream<Arguments> unexpectedSources() {
        return Stream.concat(Stream.of(Arguments.of(AnalysisType.VIDEO, "audio"),
                        Arguments.of(AnalysisType.AUDIO, "video")),
                Stream.of(AnalysisType.values()).flatMap(type -> Stream.of("unknown", "VIDEO", "", 42, null)
                        .map(source -> Arguments.of(type, source))));
    }

    @ParameterizedTest
    @MethodSource("unexpectedSources")
    void unexpectedOrInvalidSourceCannotAcceptSuccessOrFailure(AnalysisType type, Object source) {
        UUID id = seed(type);
        Map<String, Object> before = stored(id);
        for (String status : List.of("COMPLETED", "FAILED")) {
            Map<String, Object> payload = new HashMap<>(completed(id, "video", "0.8", "invalid"));
            payload.put("source", source);
            payload.put("status", status);
            service.handleResult(payload);
            assertThat(stored(id)).isEqualTo(before);
        }
        assertThat(redis.keys("dedup:*")).isEmpty();
        verifyNoInteractions(streams, metrics);
        assertCapacityOccupied();
    }

    @Test
    void nonTerminalStatusDoesNotConsumeTheSource() {
        UUID id = seed(AnalysisType.VIDEO);
        Map<String, Object> before = stored(id);
        for (Object status : new Object[]{"PROCESSING", "CANCELLED", "complete", null, 42}) {
            Map<String, Object> payload = new HashMap<>(completed(id, "video", "0.8", "invalid"));
            payload.put("status", status);
            service.handleResult(payload);
            assertThat(stored(id)).isEqualTo(before);
        }
        assertThat(idempotency.alreadyProcessed(id, "video")).isFalse();
        service.handleResult(completed(id, "video", "0.8", "valid"));
        assertThat(stored(id).get("status")).isEqualTo("COMPLETED");
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisType.class)
    void actualCommitFailureLeavesTheSourceRetryable(AnalysisType type) {
        UUID id = seed(type);
        Map<String, Object> before = stored(id);
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_result_commit() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'result acceptance deferred commit failure'; END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_result_commit AFTER UPDATE ON analysis
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_result_commit()
                """);
        String source = type == AnalysisType.AUDIO ? "audio" : "video";
        assertThatThrownBy(() -> service.handleResult(completed(id, source, "0.8", "first")))
                .hasStackTraceContaining("result acceptance deferred commit failure");
        assertThat(stored(id)).isEqualTo(before);
        assertThat(idempotency.alreadyProcessed(id, source)).isFalse();
        verifyNoInteractions(streams);
        assertCapacityOccupied();
        dropCommitTrigger();
        service.handleResult(completed(id, source, "0.1", "retry"));
        assertThat((BigDecimal) stored(id).get(source + "_prob")).isEqualByComparingTo("0.1");
        assertThat(idempotency.alreadyProcessed(id, source)).isTrue();
    }

    @Test
    void rolledBackSourceFailureCanBeRetriedAsSuccess() {
        UUID id = seed(AnalysisType.FULL);
        Map<String, Object> before = stored(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            service.handleResult(failed(id, "video"));
            tx.setRollbackOnly();
        });
        assertThat(stored(id)).isEqualTo(before);
        assertThat(idempotency.alreadyProcessed(id, "video")).isFalse();
        verifyNoInteractions(streams);
        assertCapacityOccupied();
        service.handleResult(completed(id, "video", "0.8", "retry"));
        service.handleResult(completed(id, "audio", "0.4", "audio"));
        assertFull(id, "0.8", "0.4", "FAKE", "0.28");
        verify(streams, times(1)).sendResult(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalAnalysisIgnoresAllLateResultsWithoutFreeingAnotherPlace(AnalysisStatus status) {
        UUID id = seed(AnalysisType.FULL);
        service.handleResult(completed(id, "video", "0.8", "first"));
        switch (status) {
            case COMPLETED -> service.handleResult(completed(id, "audio", "0.4", "audio"));
            case FAILED -> service.handleResult(failed(id, "audio"));
            case CANCELLED -> service.cancel(id, "alice");
            default -> throw new IllegalArgumentException("Expected terminal status");
        }
        Map<String, Object> terminal = stored(id);
        assertThat(terminal.get("status")).isEqualTo(status.name());
        create();
        clearInvocations(streams, metrics, cache);
        for (String source : List.of("video", "audio")) {
            for (Map<String, Object> late : List.of(completed(id, source, "0.1", "late"), failed(id, source))) {
                idempotency.clear(id);
                service.handleResult(late);
                assertThat(stored(id)).isEqualTo(terminal);
                assertCapacityOccupied();
            }
        }
        verifyNoInteractions(streams, metrics, cache);
    }

    private UUID seed(AnalysisType type) {
        return repository.saveAndFlush(Analysis.builder().userId("alice").fileId("file")
                .fileKey("key").type(type).build()).getId();
    }

    private UUID create() {
        return service.createResolved(new CreateAnalysisRequest("file", "key", AnalysisType.VIDEO, null), "alice").id();
    }

    private void assertCapacityOccupied() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis WHERE status IN ('PENDING', 'PROCESSING')", Long.class))
                .isEqualTo(1);
        assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
    }

    private Map<String, Object> stored(UUID id) {
        return jdbc.queryForMap("SELECT *, video_details::text AS video_details, audio_details::text AS audio_details FROM analysis WHERE id = ?", id);
    }

    private void assertFull(UUID id, String video, String audio, String verdict, String confidence) {
        Map<String, Object> row = stored(id);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("verdict")).isEqualTo(verdict);
        assertThat((BigDecimal) row.get("video_prob")).isEqualByComparingTo(video);
        assertThat((BigDecimal) row.get("audio_prob")).isEqualByComparingTo(audio);
        assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo(confidence);
        assertThat(row.get("error_message")).isNull();
    }

    private static Map<String, Object> completed(UUID id, String source, String probability, String model) {
        return Map.of("analysis_id", id.toString(), "source", source, "status", "COMPLETED",
                "result", Map.of("prob_fake", probability, "model_version", model,
                        "gradcam_keys", List.of(id + "/" + source + "/" + model + ".png"),
                        "metadata", Map.of("accepted_model", model)));
    }

    private static Map<String, Object> failed(UUID id, String source) {
        return Map.of("analysis_id", id.toString(), "source", source, "status", "FAILED",
                "error", Map.of("code", "TEST", "message", "failed delivery"));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class RedisConfig {
        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        }
        @Bean
        StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }
}
