package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.exception.TooManyAnalysesException;
import com.deepfake.orchestrator.listener.AnalysisResultListener;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** Production Python processes, real confirms/ACK, Rabbit listeners and committed PostgreSQL. */
@DataJpaTest(showSql = false, properties = "backpressure.max-inflight=1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
@Import({AnalysisService.class, BackpressureGuard.class, IdempotencyGuard.class,
        RabbitConfig.class, AnalysisResultListener.class, WorkerRedeliveryIntegrationTest.RedisConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class WorkerRedeliveryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0-alpine")
            .withExposedPorts(6379);
    @Container
    static final GenericContainer<?> rabbitContainer = new GenericContainer<>("rabbitmq:4.3.1-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitContainer::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbitContainer.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired IdempotencyGuard idempotency;
    @MockitoSpyBean AnalysisResultListener listener;
    private final AtomicInteger committedResults = new AtomicInteger();
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean AnalysisMetrics metrics;
    @TempDir Path directory;
    private final List<Process> workers = new ArrayList<>();

    @BeforeEach
    void reset() {
        repository.deleteAll();
        rabbit.execute(channel -> {
            for (String queue : List.of("analysis.video", "analysis.audio", "analysis.results", "analysis.progress")) {
                channel.queuePurge(queue);
            }
            return null;
        });
        committedResults.set(0);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            committedResults.incrementAndGet(); // Transactional service returned after commit.
            return null;
        }).when(listener).onResult(any());
    }

    @AfterEach
    void stopWorkers() throws Exception {
        for (Process worker : workers) kill(worker);
    }

    @ParameterizedTest(name = "FULL restart and duplicates from {0}")
    @ValueSource(strings = {"video", "audio"})
    void confirmedResultSurvivesRestartConcurrencyAndTerminalDuplicates(String source) throws Exception {
        // Admission and dispatch use the real service and RabbitTemplate, not seeded queue counts.
        UUID id = create(AnalysisType.FULL);
        String sibling = source.equals("video") ? "audio" : "video";
        String originalScore = source.equals("video") ? "0.8" : "0.4";
        redis.opsForValue().set("processing:" + id + ":" + source, "1", Duration.ofHours(1));
        redis.opsForValue().set("processing:" + id + ":" + sibling, "1", Duration.ofHours(1));
        Process first = start(source, "first", "confirmed", originalScore, false);
        barrier("first", "confirmed"); // Real basic_publish has returned after broker ACK.
        assertThat(Files.exists(directory.resolve("first/before_ack"))).isFalse();
        await().atMost(Duration.ofSeconds(20)).until(() -> stored(id).get(source + "_prob") != null);
        Map<String, Object> partial = stored(id);
        assertCapacityOccupied();
        kill(first); // A real SIGKILL releases the broker's unacked delivery.
        awaitResults(1); // Includes afterCommit hints, not just early SQL visibility.
        idempotency.clear(id); // Prove the PostgreSQL guard independently of the Redis hint.

        assertThat(idempotency.alreadyProcessed(id, source)).isFalse();
        Process replacement = start(source, "replacement", "inference", "0.1", false);
        barrier("replacement", "inference");
        assertThat(Files.readString(directory.resolve("replacement/claimed")))
                .contains("\"redelivered\": true");
        Process concurrent = start(source, "concurrent", "inference", "0.9", true);
        dispatch(id, source);
        barrier("concurrent", "inference");
        assertThat(Files.readString(directory.resolve("replacement/inference")))
                .isNotEqualTo(Files.readString(directory.resolve("concurrent/inference")));
        release("replacement");
        release("concurrent");
        barrier("replacement", "acked");
        barrier("concurrent", "acked");
        awaitResults(3);
        assertThat(idempotency.alreadyProcessed(id, source)).isFalse();
        assertThat(stored(id).get(source + "_prob")).isEqualTo(partial.get(source + "_prob"));
        assertThat(stored(id).get(source + "_details")).isEqualTo(partial.get(source + "_details"));
        assertThat(stored(id).get("error_message")).isNull();
        assertCapacityOccupied();
        kill(replacement);
        kill(concurrent);

        Process siblingWorker = start(sibling, "sibling", "unused", sibling.equals("video") ? "0.8" : "0.4", false);
        barrier("sibling", "acked");
        await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLETED".equals(stored(id).get("status")));
        Map<String, Object> terminal = stored(id);
        assertThat((BigDecimal) terminal.get("video_prob")).isEqualByComparingTo("0.8");
        assertThat((BigDecimal) terminal.get("audio_prob")).isEqualByComparingTo("0.4");
        assertThat(terminal.get("verdict")).isEqualTo("FAKE");
        assertThat((BigDecimal) terminal.get("confidence")).isEqualByComparingTo("0.28");
        assertThat(terminal.get("audio_details").toString())
                .contains("audio-threshold-v1", "raw_prob_fake", "threshold_used");

        awaitResults(4);
        kill(siblingWorker);
        create(sibling.equals("video") ? AnalysisType.VIDEO : AnalysisType.AUDIO); // One reusable place.
        idempotency.clear(id);
        start(source, "late", "unused", "0.1", true);
        dispatch(id, source); // A confirmed FAILED duplicate of a completed FULL.
        barrier("late", "acked");
        awaitResults(5);
        assertThat(stored(id)).isEqualTo(terminal);
        assertCapacityOccupied(); // A terminal duplicate cannot free the newly occupied place.
        assertThat(redis.opsForValue().get("processing:" + id + ":" + source)).isEqualTo("1");
    }

    private UUID create(AnalysisType type) {
        return service.createResolved(new CreateAnalysisRequest("file", "key", type, null), "alice").id();
    }

    private void assertCapacityOccupied() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis WHERE status IN ('PENDING', 'PROCESSING')", Long.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> create(AnalysisType.VIDEO)).isInstanceOf(TooManyAnalysesException.class);
    }

    private Map<String, Object> stored(UUID id) {
        return jdbc.queryForMap("SELECT *, video_details::text AS video_details, audio_details::text AS audio_details FROM analysis WHERE id = ?", id);
    }

    private Process start(String source, String name, String block, String score, boolean fail) throws Exception {
        Path state = directory.resolve(name);
        Files.createDirectories(state);
        Path script = Path.of("../tests/worker_redelivery/worker.py").toAbsolutePath();
        var builder = new ProcessBuilder(System.getenv().getOrDefault("DETECTOR_TEST_PYTHON", "python3"),
                script.toString(), source, state.toString());
        var env = builder.environment();
        env.putAll(Map.of("RABBITMQ_HOST", rabbitContainer.getHost(), "RABBITMQ_PORT", rabbitContainer.getMappedPort(5672).toString(),
                "RABBITMQ_USER", "test", "RABBITMQ_PASSWORD", "test", "REDIS_HOST", redisContainer.getHost(),
                "REDIS_PORT", redisContainer.getMappedPort(6379).toString(), "AWS_EC2_METADATA_DISABLED", "true",
                "QUEUE_NAME", "analysis." + source, "SOURCE_LABEL", source, "BLOCK_AT", block));
        env.remove("REDIS_PASSWORD");
        env.put("MODEL_SCORE", score);
        env.put("MODEL_FAIL", Boolean.toString(fail));
        Process worker = builder.redirectErrorStream(true).redirectOutput(state.resolve("worker.log").toFile()).start();
        workers.add(worker);
        return worker;
    }

    private void barrier(String name, String checkpoint) {
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
            Path state = directory.resolve(name);
            assertThat(Files.exists(state.resolve(checkpoint)))
                    .withFailMessage(() -> {
                        try { return Files.readString(state.resolve("worker.log")); }
                        catch (Exception e) { return e.toString(); }
                    }).isTrue();
        });
    }

    private void release(String name) throws Exception {
        Files.createFile(directory.resolve(name).resolve("release"));
    }

    private void dispatch(UUID id, String source) {
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, "analysis." + source,
                Map.of("analysis_id", id.toString(), "file_bucket", "test", "file_key", "input", "mode", "accurate"));
    }

    private void awaitResults(int expected) {
        await().atMost(Duration.ofSeconds(20)).until(() -> committedResults.get() == expected);
    }

    private static void kill(Process process) throws Exception {
        if (process.isAlive()) process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }

    @TestConfiguration
    static class RedisConfig {
        @Bean LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        }
        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }
    }
}
