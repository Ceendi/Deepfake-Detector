package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.listener.AnalysisResultListener;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Python-produced wire bytes cross the production Rabbit listener, transaction, SQL and REST mapping. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
@Import({AnalysisService.class, RabbitConfig.class, AnalysisResultListener.class,
        IdempotencyGuard.class, AudioScoreContractIntegrationTest.RedisConfig.class})
@EnableRabbit
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AudioScoreContractIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine")
            .withNetwork(NETWORK);
    @Container
    static final GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:4.3.1-alpine")
            .withNetwork(NETWORK).withEnv("RABBITMQ_DEFAULT_USER", "test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "test").withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void connections(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BackpressureGuard backpressure;
    @Autowired IdempotencyGuard idempotency;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @Autowired StringRedisTemplate redis;
    @MockitoBean AnalysisMetrics metrics;

    static Stream<Arguments> fixtures() throws Exception {
        try (var input = AudioScoreContractIntegrationTest.class.getResourceAsStream(
                "/contracts/audio-score-results.json")) {
            List<Map<String, Object>> cases = JSON.readValue(input, new TypeReference<>() {});
            return cases.stream().flatMap(c -> Stream.of(AnalysisType.AUDIO, AnalysisType.FULL)
                    .map(type -> Arguments.of(c.get("label"), type, c)));
        }
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("fixtures")
    @SuppressWarnings("unchecked")
    void wireResultIsCommittedAndMappedConsistently(String label, AnalysisType type,
                                                   Map<String, Object> fixture) {
        UUID id = repository.saveAndFlush(Analysis.builder().userId("audio-contract")
                .fileId("fixture.wav").fileKey("fixture.wav").type(type).build()).getId();
        Map<String, Object> audio = new HashMap<>((Map<String, Object>) fixture.get("payload"));
        audio.put("analysis_id", id.toString());
        Map<String, Object> result = (Map<String, Object>) audio.get("result");
        BigDecimal probability = decimal(result.get("prob_fake"));
        BigDecimal finalProbability = type == AnalysisType.FULL
                ? new BigDecimal("0.5").multiply(new BigDecimal("0.6"))
                    .add(probability.multiply(new BigDecimal("0.4")))
                : probability;
        if (type == AnalysisType.FULL) {
            publish(Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED",
                    "result", Map.of("prob_fake", 0.5)));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                Analysis partial = repository.findById(id).orElseThrow();
                assertThat(partial.getVideoProb()).isEqualByComparingTo("0.5");
                assertThat(partial.getStatus()).isEqualTo(AnalysisStatus.PENDING);
            });
        }
        publish(audio);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(AnalysisStatus.COMPLETED));

        // Independent SQL reads after the listener transaction has committed.
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT audio_prob, verdict, confidence, audio_details::text AS details FROM analysis WHERE id = ?", id);
        assertThat((BigDecimal) stored.get("audio_prob")).isEqualByComparingTo(probability);
        String expectedVerdict = finalProbability.compareTo(new BigDecimal("0.5")) > 0 ? "FAKE" : "REAL";
        assertThat(stored.get("verdict")).isEqualTo(expectedVerdict).isEqualTo(result.get("verdict"));
        BigDecimal confidence = finalProbability.subtract(new BigDecimal("0.5")).abs()
                .multiply(new BigDecimal("2")).setScale(4, RoundingMode.HALF_UP);
        assertThat((BigDecimal) stored.get("confidence")).isEqualByComparingTo(confidence);
        Map<String, Object> details = JSON.readValue(stored.get("details").toString(), new TypeReference<>() {});
        assertThat(details.get("verdict")).isEqualTo(result.get("verdict"));
        assertThat(decimal(details.get("confidence"))).isEqualByComparingTo(decimal(result.get("confidence")));
        Map<String, Object> metadata = (Map<String, Object>) details.get("metadata");
        assertThat(metadata).containsEntry("score_contract", "audio-threshold-v1")
                .containsEntry("mode_used", fixture.get("mode"));
        assertThat(decimal(metadata.get("raw_prob_fake"))).isEqualByComparingTo(decimal(fixture.get("raw_score")));
        assertThat(decimal(metadata.get("threshold_used"))).isEqualByComparingTo(decimal(fixture.get("threshold")));
        assertThat(JSON.readTree(JSON.writeValueAsString(metadata.get("segment_predictions"))))
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(((Map<?, ?>) result.get("metadata")).get("segment_predictions"))));
        assertThat(metadata.get("insights")).isEqualTo(((Map<?, ?>) result.get("metadata")).get("insights"));

        AnalysisResponse response = service.get(id, "audio-contract");
        assertThat(response.audioProb()).isEqualByComparingTo(probability);
        assertThat(response.verdict()).isEqualTo(expectedVerdict);
        assertThat(response.confidence()).isEqualByComparingTo(confidence);
        assertThat(JSON.readTree(JSON.writeValueAsString(response.details().get("audio"))))
                .isEqualTo(JSON.readTree(JSON.writeValueAsString(details)));
        if (type == AnalysisType.AUDIO) {
            assertThat(response.confidence()).isEqualByComparingTo(decimal(result.get("confidence")));
        }
    }

    @ParameterizedTest(name = "{0} / AUDIO")
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void audioNotApplicableWireFailureHasNoAuthenticityVerdict(String reason) throws Exception {
        UUID id = repository.saveAndFlush(Analysis.builder().userId("audio-contract")
                .fileId("silent.wav").fileKey("silent.wav").type(AnalysisType.AUDIO).build()).getId();
        Map<String, Object> audio = notApplicable(id, reason);
        publish(audio);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(AnalysisStatus.FAILED));

        Map<?, ?> error = (Map<?, ?>) audio.get("error");
        AnalysisResponse response = service.get(id, "audio-contract");
        assertThat(response.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(response.errorMessage()).isEqualTo("[" + reason + "] " + error.get("message"));
        assertThat(response.audioProb()).isNull();
        assertThat(response.verdict()).isNull();
        assertThat(response.confidence()).isNull();
    }

    @ParameterizedTest(name = "FULL {0} audio first: {1}")
    @CsvSource({"NO_SPEECH,false", "NO_SPEECH,true", "NO_AUDIO_TRACK,false", "NO_AUDIO_TRACK,true"})
    void fullAudioNotApplicableWireMarkerCompletesFromVideoInEitherOrder(String reason, boolean audioFirst) throws Exception {
        UUID id = newFull();
        if (audioFirst) {
            publish(notApplicable(id, reason));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                Analysis partial = repository.findById(id).orElseThrow();
                assertThat(partial.getAudioDetails()).containsEntry("reason", reason);
                assertThat(partial.getStatus()).isEqualTo(AnalysisStatus.PENDING);
                assertThat(partial.getVerdict()).isNull();
            });
            publish(success(id, "video", "0.8"));
        } else {
            publish(success(id, "video", "0.8"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(repository.findById(id).orElseThrow().getVideoProb())
                            .isEqualByComparingTo("0.8"));
            publish(notApplicable(id, reason));
        }
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED));
        assertVideoOnly(id, reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void durableMarkerRejectsLateAudioSuccessFailureAndDlqAfterRedisLoss(String reason) throws Exception {
        UUID id = newFull();
        service.handleResult(notApplicable(id, reason));
        assertThat(idempotency.alreadyProcessed(id, "audio")).isTrue();
        idempotency.clear(id); // Real Redis hints lost; the committed JSONB outcome remains authoritative.
        service.handleResult(success(id, "audio", "0.1"));
        service.handleResult(failure(id, "audio", "MODEL_UNAVAILABLE"));
        service.failFromDlq(id, "audio", "late audio task");
        Analysis pending = repository.findById(id).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(pending.getAudioProb()).isNull();
        assertThat(pending.getAudioDetails()).containsEntry("reason", reason);
        service.handleResult(success(id, "video", "0.8"));
        assertVideoOnly(id, reason);
        idempotency.clear(id);
        service.handleResult(success(id, "audio", "0.9"));
        service.handleResult(notApplicable(id, reason));
        assertVideoOnly(id, reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void acceptedAudioScoreRejectsLateAudioNotApplicableAndKeepsWeightedFull(String reason) throws Exception {
        UUID id = newFull();
        service.handleResult(success(id, "audio", "0.1"));
        idempotency.clear(id);
        service.handleResult(notApplicable(id, reason));
        service.handleResult(success(id, "video", "0.8"));
        AnalysisResponse response = service.get(id, "audio-contract");
        assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(response.audioProb()).isEqualByComparingTo("0.1");
        assertThat(response.confidence()).isEqualByComparingTo("0.04"); // .6*.8 + .4*.1 = .52
        assertThat(response.verdict()).isEqualTo("FAKE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MODEL_UNAVAILABLE", "PROCESSING_ERROR", "VIDEO_DECODE_FAILED", "NO_FACE_DETECTED", "MEDIA_TOO_LONG"})
    void otherAudioFailuresRemainFatalForFull(String code) {
        UUID id = newFull();
        service.handleResult(failure(id, "audio", code));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void videoFailureAfterAudioMarkerStillFailsFull(String reason) throws Exception {
        UUID id = newFull();
        service.handleResult(notApplicable(id, reason));
        service.handleResult(failure(id, "video", reason));
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    @ParameterizedTest
    @MethodSource("terminalMarkers")
    void terminalAnalysisRejectsAudioNotApplicableMarker(String reason, AnalysisStatus status) throws Exception {
        Analysis analysis = repository.saveAndFlush(Analysis.builder().userId("audio-contract")
                .fileId("f").fileKey("k").type(AnalysisType.FULL).build());
        jdbc.update("UPDATE analysis SET status = ? WHERE id = ?", status.name(), analysis.getId());
        service.handleResult(notApplicable(analysis.getId(), reason));
        Analysis stored = repository.findById(analysis.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(status);
        assertThat(stored.getAudioDetails()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void rolledBackMarkerLeavesNoDatabaseOrRedisAcceptanceAndCanReplay(String reason) throws Exception {
        UUID id = newFull();
        Map<String, Object> payload = notApplicable(id, reason);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.handleResult(payload);
            throw new IllegalStateException("controlled rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.findById(id).orElseThrow().getAudioDetails()).isNull();
        assertThat(idempotency.alreadyProcessed(id, "audio")).isFalse();
        service.handleResult(success(id, "video", "0.8"));
        publish(payload);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.COMPLETED));
        assertVideoOnly(id, reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void concurrentVideoAndAudioNotApplicableCompleteExactlyOnce(String reason) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 10; i++) {
                UUID id = newFull();
                CountDownLatch start = new CountDownLatch(1);
                Map<String, Object> audio = notApplicable(id, reason);
                var videoTask = pool.submit(() -> { start.await(); service.handleResult(success(id, "video", "0.8")); return null; });
                var audioTask = pool.submit(() -> { start.await(); service.handleResult(audio); return null; });
                start.countDown();
                videoTask.get(15, TimeUnit.SECONDS);
                audioTask.get(15, TimeUnit.SECONDS);
                assertVideoOnly(id, reason);
                verify(streams, times(1)).sendResult(eq(id), any());
            }
        }
    }

    static Stream<Arguments> competingAudioNotApplicableOutcomes() {
        return Stream.of(false, true).flatMap(failure -> Stream.of(false, true)
                .flatMap(rollback -> Stream.of("NO_SPEECH", "NO_AUDIO_TRACK")
                        .map(reason -> Arguments.of(failure, rollback, reason))));
    }

    @ParameterizedTest(name = "held {2} marker: competing failure={0}, rollback={1}")
    @MethodSource("competingAudioNotApplicableOutcomes")
    void heldMarkerTransactionProtectsAcceptanceUntilCommitOrRollback(boolean failure,
                                                                      boolean rollback, String reason) throws Exception {
        UUID id = newFull();
        Map<String, Object> marker = notApplicable(id, reason);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                service.handleResult(marker);
                written.countDown();
                try {
                    if (!finish.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("marker barrier timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                if (rollback) tx.setRollbackOnly();
            }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(repository.findById(id).orElseThrow().getAudioDetails()).isNull();
                assertThat(idempotency.alreadyProcessed(id, "audio")).isFalse();
                var second = pool.submit(() -> service.handleResult(failure
                        ? failure(id, "audio", "MODEL_UNAVAILABLE") : success(id, "audio", "0.1")));
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
        Analysis accepted = repository.findById(id).orElseThrow();
        if (rollback && failure) {
            assertThat(accepted.getStatus()).isEqualTo(AnalysisStatus.FAILED);
            assertThat(accepted.getAudioDetails()).isNull();
            assertThat(accepted.getAudioProb()).isNull();
        } else {
            assertThat(accepted.getStatus()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(accepted.getAudioDetails() != null && reason.equals(accepted.getAudioDetails().get("reason"))).isEqualTo(!rollback);
            assertThat(accepted.getAudioProb() == null).isEqualTo(!rollback);
            service.handleResult(success(id, "video", "0.8"));
            if (rollback) {
                AnalysisResponse response = service.get(id, "audio-contract");
                assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
                assertThat(response.audioProb()).isEqualByComparingTo("0.1");
                assertThat(response.confidence()).isEqualByComparingTo("0.04");
            } else {
                assertVideoOnly(id, reason);
            }
        }
        assertThat(idempotency.alreadyProcessed(id, "audio")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_SPEECH", "NO_AUDIO_TRACK"})
    void videoAnalysisIgnoresUnexpectedAudioNotApplicable(String reason) throws Exception {
        Analysis analysis = repository.saveAndFlush(Analysis.builder().userId("audio-contract")
                .fileId("f").fileKey("k").type(AnalysisType.VIDEO).build());
        service.handleResult(notApplicable(analysis.getId(), reason));
        Analysis stored = repository.findById(analysis.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(stored.getAudioDetails()).isNull();
        assertThat(idempotency.alreadyProcessed(analysis.getId(), "audio")).isFalse();
    }

    @Test
    void arbitraryAudioDetailsDoNotSatisfyFullReadiness() {
        Analysis analysis = repository.saveAndFlush(Analysis.builder().userId("audio-contract")
                .fileId("f").fileKey("k").type(AnalysisType.FULL)
                .audioDetails(Map.of("status", "NOT_APPLICABLE", "reason", "OTHER")).build());
        service.handleResult(success(analysis.getId(), "video", "0.8"));
        assertThat(repository.findById(analysis.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisStatus.PENDING);
    }

    static Stream<Arguments> terminalMarkers() {
        return Stream.of("NO_SPEECH", "NO_AUDIO_TRACK").flatMap(reason ->
                Stream.of(AnalysisStatus.CANCELLED, AnalysisStatus.FAILED, AnalysisStatus.COMPLETED)
                        .map(status -> Arguments.of(reason, status)));
    }

    private UUID newFull() {
        return repository.saveAndFlush(Analysis.builder().userId("audio-contract")
                .fileId("f").fileKey("k").type(AnalysisType.FULL).build()).getId();
    }

    private Map<String, Object> notApplicable(UUID id, String reason) throws Exception {
        try (var input = getClass().getResourceAsStream("/contracts/" + ("NO_SPEECH".equals(reason) ? "audio-no-speech-result.json"
                            : "audio-no-track-result.json"))) {
            Map<String, Object> payload = JSON.readValue(input, new TypeReference<>() {});
            payload.put("analysis_id", id.toString());
            return payload;
        }
    }

    private static Map<String, Object> success(UUID id, String source, String probability) {
        return Map.of("analysis_id", id.toString(), "source", source, "status", "COMPLETED",
                "result", Map.of("prob_fake", probability));
    }

    private static Map<String, Object> failure(UUID id, String source, String code) {
        return Map.of("analysis_id", id.toString(), "source", source, "status", "FAILED",
                "error", Map.of("code", code, "message", "controlled failure"));
    }

    private void assertVideoOnly(UUID id, String reason) {
        AnalysisResponse response = service.get(id, "audio-contract");
        assertThat(response.status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(response.videoProb()).isEqualByComparingTo("0.8");
        assertThat(response.audioProb()).isNull();
        assertThat(response.verdict()).isEqualTo("FAKE");
        assertThat(response.confidence()).isEqualByComparingTo("0.6");
        assertThat(response.errorMessage()).isNull();
        Map<?, ?> marker = (Map<?, ?>) response.details().get("audio");
        assertThat(marker.get("status")).isEqualTo("NOT_APPLICABLE");
        assertThat(marker.get("reason")).isEqualTo(reason);
        assertThat(marker.get("message")).isInstanceOf(String.class);
        assertThat(marker).hasSize(3); // No fake audio verdict/model/metadata/artifacts.
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisConfig {
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        }
        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }

    private void publish(Map<String, Object> payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        rabbitTemplate.send(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS,
                new Message(JSON.writeValueAsBytes(payload), properties));
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(value.toString());
    }
}
