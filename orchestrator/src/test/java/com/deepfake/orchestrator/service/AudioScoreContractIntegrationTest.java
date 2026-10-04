package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.testcontainers.containers.PostgreSQLContainer;
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
@Import({AnalysisService.class, RabbitConfig.class, AnalysisResultListener.class})
@EnableRabbit
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AudioScoreContractIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withNetwork(NETWORK);
    @Container
    static final GenericContainer<?> rabbit = new GenericContainer<>("rabbitmq:4.3.1-alpine")
            .withNetwork(NETWORK).withEnv("RABBITMQ_DEFAULT_USER", "test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "test").withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

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
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean StringRedisTemplate redis;
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
