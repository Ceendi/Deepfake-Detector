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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

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
@DataJpaTest(showSql = false, properties = {"backpressure.max-inflight=1", "reliability.outbox.retry-seconds=1", "reliability.outbox.confirm-timeout-ms=1000"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
@Import({AnalysisService.class, BackpressureGuard.class, IdempotencyGuard.class,
        RabbitConfig.class, TaskOutboxPublisher.class, AnalysisResultListener.class, TransactionalDispatchIntegrationTest.RedisConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TransactionalDispatchIntegrationTest {
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

    @MockitoBean(name = "org.springframework.context.annotation.internalScheduledAnnotationProcessor")
    org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor scheduling;
    @Autowired AnalysisService service;
    @Autowired TaskOutboxPublisher publisher;
    @Autowired AnalysisRepository repository;
    @MockitoSpyBean RabbitTemplate rabbit;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired org.springframework.amqp.rabbit.core.RabbitAdmin admin;
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
    void resetState() {
        reset(rabbit);
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

    @Test
    void rolledBackCreatePublishesNothingAndDoesNotConsumeCapacity() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            create(AnalysisType.FULL);
            publisher.dispatchDue(); // Separate transaction cannot read this pending insert.
            assertThat(rabbit.receive("analysis.video")).isNull();
            assertThat(rabbit.receive("analysis.audio")).isNull();
            status.setRollbackOnly();
        });
        assertThat(repository.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task_outbox", Long.class)).isZero();
        publisher.dispatchDue();
        assertThat(rabbit.receive("analysis.video")).isNull();
        assertThat(create(AnalysisType.VIDEO)).isNotNull();
    }

    @Test
    void fastestProductionWorkerAlwaysSeesACommittedAnalysis() throws Exception {
        start("video", "fast", "unused", "0.8", false);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        UUID id = transaction.execute(status -> {
            UUID pending = create(AnalysisType.VIDEO);
            publisher.dispatchDue();
            assertThat(Files.exists(directory.resolve("fast/claimed"))).isFalse();
            return pending;
        });
        assertCapacityOccupied();
        publisher.dispatchDue();
        barrier("fast", "acked");
        awaitResults(1);
        assertThat(stored(id).get("status")).isEqualTo("COMPLETED");
        assertThat((BigDecimal) stored(id).get("video_prob")).isEqualByComparingTo("0.8");
        assertThat(sent(id, "video")).isTrue();
        create(AnalysisType.AUDIO); // PostgreSQL capacity really became reusable.
    }

    @Test
    void brokerOutageRetainsCommittedTaskAndRetriesAfterReconnect() throws Exception {
        assertThat(rabbitContainer.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
        UUID id;
        try {
            ((org.springframework.amqp.rabbit.connection.CachingConnectionFactory) rabbit.getConnectionFactory()).resetConnection();
            id = create(AnalysisType.VIDEO);
            publisher.dispatchDue();
            assertThat(repository.findById(id)).isPresent();
            assertThat(sent(id, "video")).isFalse();
            assertCapacityOccupied();
            assertThat(attempts(id, "video")).isEqualTo(1);
            publisher.dispatchDue();
            assertThat(attempts(id, "video")).isEqualTo(1); // Persisted backoff prevents a hot retry.
        } finally {
            assertThat(rabbitContainer.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(45)).ignoreExceptions().until(() -> rabbit.execute(channel -> channel.isOpen()));
        start("video", "recovered", "unused", "0.8", false);
        due(id);
        publisher.dispatchDue();
        barrier("recovered", "acked");
        awaitResults(1);
        assertThat(stored(id).get("status")).isEqualTo("COMPLETED");
        assertThat(sent(id, "video")).isTrue();
        create(AnalysisType.AUDIO);
    }

    @Test
    void fullRetriesOnlyTheReturnedSourceAndKeepsItsTaskIdentity() throws Exception {
        UUID id = create(AnalysisType.FULL);
        String before = jdbc.queryForObject("SELECT payload::text FROM analysis_task_outbox WHERE analysis_id=? AND source='audio'", String.class, id);
        rabbit.execute(channel -> { channel.queueUnbind("analysis.audio", RabbitConfig.EXCHANGE, "analysis.audio"); return null; });
        try {
            publisher.dispatchDue();
            assertThat(sent(id, "video")).isTrue();
            assertThat(sent(id, "audio")).isFalse(); // Real mandatory return + ACK is not success.
            assertThat(attempts(id, "audio")).isEqualTo(1);
            start("video", "partial", "unused", "0.8", false);
            barrier("partial", "acked");
            awaitResults(1);
            assertThat(stored(id).get("status")).isEqualTo("PROCESSING");
            assertCapacityOccupied();
        } finally {
            rabbit.execute(channel -> { channel.queueBind("analysis.audio", RabbitConfig.EXCHANGE, "analysis.audio"); return null; });
        }
        due(id);
        publisher.dispatchDue();
        assertThat(attempts(id, "video")).isEqualTo(1);
        assertThat(attempts(id, "audio")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM analysis_task_outbox WHERE analysis_id=? AND source='audio'", String.class, id)).isEqualTo(before);
        start("audio", "remaining", "unused", "0.4", false);
        barrier("remaining", "acked");
        awaitResults(2);
        assertThat(stored(id).get("status")).isEqualTo("COMPLETED");
        assertThat((BigDecimal) stored(id).get("confidence")).isEqualByComparingTo("0.28");
        create(AnalysisType.VIDEO);
    }

    @Test
    void realBrokerNackDoesNotMarkTheTaskSent() {
        rabbit.execute(channel -> {
            channel.queueDelete("analysis.video");
            channel.queueDeclare("analysis.video", true, false, false, Map.of("x-max-length", 1, "x-overflow", "reject-publish"));
            channel.queueBind("analysis.video", RabbitConfig.EXCHANGE, "analysis.video");
            return null;
        });
        try {
            rabbit.convertAndSend(RabbitConfig.EXCHANGE, "analysis.video", Map.of("fixture", "fills queue"));
            UUID id = create(AnalysisType.VIDEO);
            publisher.dispatchDue();
            assertThat(sent(id, "video")).isFalse();
            assertThat(attempts(id, "video")).isEqualTo(1);
            assertCapacityOccupied();
            assertThat(rabbit.receive("analysis.video")).isNotNull();
            due(id);
            publisher.dispatchDue();
            assertThat(sent(id, "video")).isTrue();
        } finally {
            rabbit.execute(channel -> { channel.queueDelete("analysis.video"); return null; });
            admin.initialize();
        }
    }

    @Test
    void cancelBeforeDispatchAndTerminalFailureSuppressEverySource() {
        UUID cancelled = create(AnalysisType.FULL);
        service.cancel(cancelled, "alice");
        publisher.dispatchDue();
        assertThat(rabbit.receive("analysis.video")).isNull();
        assertThat(rabbit.receive("analysis.audio")).isNull();
        assertThat(attempts(cancelled, "video")).isZero();
        UUID failed = create(AnalysisType.VIDEO);
        service.failFromDlq(failed, "test failure before dispatch");
        publisher.dispatchDue();
        assertThat(rabbit.receive("analysis.video")).isNull();
        assertThat(stored(cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(stored(failed).get("status")).isEqualTo("FAILED");
        create(AnalysisType.AUDIO);
    }

    @Test
    void publisherConfirmSerializesWithCancelAndRecovery() throws Exception {
        UUID id = create(AnalysisType.FULL);
        var confirmed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            org.springframework.amqp.rabbit.connection.CorrelationData correlation = invocation.getArgument(3);
            assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
            confirmed.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(org.springframework.amqp.core.Message.class), any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var publishing = pool.submit(publisher::dispatchDue);
            assertThat(confirmed.await(10, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> service.cancel(id, "alice"));
            var recovery = pool.submit(() -> service.failStuck(id, 600));
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%analysis%'", Long.class) >= 2);
            release.countDown();
            publishing.get(20, TimeUnit.SECONDS);
            cancel.get(20, TimeUnit.SECONDS);
            recovery.get(20, TimeUnit.SECONDS);
        } finally { release.countDown(); reset(rabbit); }
        assertThat(stored(id).get("status")).isEqualTo("CANCELLED");
        var stable = stored(id);
        publisher.dispatchDue();
        // Cooperative cancel stops queued work even when publication won the row lock first.
        start("video", "cancel-video", "unused", "0.8", false);
        start("audio", "cancel-audio", "unused", "0.4", false);
        await().atMost(Duration.ofSeconds(10)).until(() -> redis.hasKey("cancel:" + id));
        service.handleProgress(Map.of("analysis_id", id.toString(), "progress", 99));
        service.handleResult(Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED", "result", Map.of("prob_fake", 0.9)));
        assertThat(stored(id)).isEqualTo(stable);
        create(AnalysisType.AUDIO);
    }

    @Test
    void staleRecoveryCandidateDoesNotKillNewProgressOrConfirmedDispatch() {
        UUID id = create(AnalysisType.VIDEO);
        jdbc.update("UPDATE analysis SET updated_at=CURRENT_TIMESTAMP - INTERVAL '700 seconds' WHERE id=?", id);
        // A fresh unsent outbox uses its finite dispatch deadline, rather than the progress clock.
        assertThat(repository.findExpiredIds(java.time.Instant.now().minusSeconds(600), java.time.Instant.now().minusSeconds(120))).doesNotContain(id);
        publisher.dispatchDue();
        service.failStuck(id, 600); // Old scan decision must not survive a new confirmed dispatch.
        assertThat(stored(id).get("status")).isEqualTo("PENDING");
        jdbc.update("UPDATE analysis SET updated_at=CURRENT_TIMESTAMP - INTERVAL '700 seconds' WHERE id=?", id);
        assertThat(repository.findExpiredIds(java.time.Instant.now().minusSeconds(600), java.time.Instant.now().minusSeconds(120))).contains(id);
        service.handleProgress(Map.of("analysis_id", id.toString(), "progress", 50));
        service.failStuck(id, 600);
        assertThat(stored(id).get("status")).isEqualTo("PROCESSING");
    }

    @Test
    void expiredMissingFullSourceAndLegacyPendingRowsFailWithoutReplay() {
        UUID id = create(AnalysisType.FULL);
        jdbc.update("UPDATE analysis_task_outbox SET sent_at=CURRENT_TIMESTAMP WHERE analysis_id=? AND source='video'", id);
        jdbc.update("UPDATE analysis SET created_at=CURRENT_TIMESTAMP - INTERVAL '121 seconds' WHERE id=?", id);
        service.handleProgress(Map.of("analysis_id", id.toString(), "progress", 90));
        assertThat(repository.findExpiredIds(java.time.Instant.now().minusSeconds(600), java.time.Instant.now().minusSeconds(120))).contains(id);
        publisher.dispatchDue();
        assertThat(rabbit.receive("analysis.audio")).isNull();
        service.failStuck(id, 600);
        assertThat(stored(id).get("status")).isEqualTo("FAILED");
        UUID legacy = create(AnalysisType.AUDIO);
        jdbc.update("DELETE FROM analysis_task_outbox WHERE analysis_id=?", legacy);
        jdbc.update("UPDATE analysis SET updated_at=CURRENT_TIMESTAMP - INTERVAL '601 seconds' WHERE id=?", legacy);
        service.failStuck(legacy, 600);
        publisher.dispatchDue();
        assertThat(stored(legacy).get("status")).isEqualTo("FAILED");
        assertThat(rabbit.receive("analysis.audio")).isNull();
        create(AnalysisType.VIDEO);
    }

    @Test
    void killAfterConfirmThenRestartTheRealOrchestratorSafelyDuplicatesFull() throws Exception {
        UUID id = create(AnalysisType.FULL);
        // Force video first so the crash deterministically leaves FULL partially dispatched.
        jdbc.update("UPDATE analysis_task_outbox SET next_attempt_at=CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE analysis_id=? AND source='audio'", id);
        Process crashing = orchestrator("crashing", true);
        Path confirmed = directory.resolve("crashing/confirmed");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(crashing.isAlive()).withFailMessage(() -> processLog("crashing")).isTrue();
            assertThat(Files.exists(confirmed)).withFailMessage(() -> processLog("crashing")).isTrue();
        });
        String taskId = Files.readString(confirmed);
        assertThat(sent(id, "video")).isFalse(); // Broker ACK is not yet a database commit.
        kill(crashing);
        assertThat(attempts(id, "video")).isZero(); // SIGKILL rolled back the in-flight DB transaction.
        assertThat(jdbc.queryForObject("SELECT id::text FROM analysis_task_outbox WHERE analysis_id=? AND source='video'", String.class, id)).isEqualTo(taskId);

        Process first = start("video", "first-crash-result", "unused", "0.8", false);
        barrier("first-crash-result", "acked");
        awaitResults(1);
        Map<String, Object> partial = stored(id);
        assertCapacityOccupied();
        kill(first);
        idempotency.clear(id);
        due(id);
        Process restarted = orchestrator("restarted", false);
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(restarted.isAlive()).withFailMessage(() -> processLog("restarted")).isTrue();
            assertThat(sent(id, "video")).isTrue();
            assertThat(sent(id, "audio")).isTrue();
        });
        kill(restarted);
        // Inspect the actual retry identity, requeueing the same delivery for the production worker.
        rabbit.execute(channel -> {
            var message = channel.basicGet("analysis.video", false);
            assertThat(message).isNotNull();
            assertThat(message.getProps().getMessageId()).isEqualTo(taskId);
            assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)).contains(taskId);
            channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
            return null;
        });
        start("video", "crash-duplicate", "unused", "0.1", false);
        barrier("crash-duplicate", "acked");
        awaitResults(2);
        assertThat(stored(id).get("video_prob")).isEqualTo(partial.get("video_prob"));
        assertThat(stored(id).get("video_details")).isEqualTo(partial.get("video_details"));
        assertCapacityOccupied();
        start("audio", "crash-audio", "unused", "0.4", false);
        barrier("crash-audio", "acked");
        awaitResults(3);
        assertThat(stored(id).get("status")).isEqualTo("COMPLETED");
        assertThat((BigDecimal) stored(id).get("confidence")).isEqualByComparingTo("0.28");
        assertThat(stored(id).get("audio_details").toString()).contains("audio-threshold-v1");
        create(AnalysisType.AUDIO);
    }

    private Process orchestrator(String name, boolean crash) throws Exception {
        Path state = directory.resolve(name);
        Files.createDirectories(state);
        var arguments = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                OutboxProcessProbe.class.getName(), "--server.port=0", "--eureka.client.enabled=false",
                "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(), "--spring.data.redis.host=" + redisContainer.getHost(),
                "--spring.data.redis.port=" + redisContainer.getMappedPort(6379), "--spring.data.redis.password=",
                "--spring.rabbitmq.host=" + rabbitContainer.getHost(), "--spring.rabbitmq.port=" + rabbitContainer.getMappedPort(5672),
                "--spring.rabbitmq.username=test", "--spring.rabbitmq.password=test",
                "--spring.rabbitmq.listener.simple.auto-startup=false", "--reliability.outbox.retry-seconds=1"));
        var builder = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(state.resolve("orchestrator.log").toFile());
        if (crash) builder.environment().put("OUTBOX_CONFIRM_BARRIER", state.resolve("confirmed").toString());
        var process = builder.start();
        workers.add(process);
        return process;
    }
    private String processLog(String name) {
        try { return Files.readString(directory.resolve(name).resolve("orchestrator.log")); }
        catch (Exception failure) { return failure.toString(); }
    }

    private boolean sent(UUID id, String source) {
        return jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM analysis_task_outbox WHERE analysis_id=? AND source=?", Boolean.class, id, source);
    }
    private int attempts(UUID id, String source) {
        return jdbc.queryForObject("SELECT attempts FROM analysis_task_outbox WHERE analysis_id=? AND source=?", Integer.class, id, source);
    }
    private void due(UUID id) {
        jdbc.update("UPDATE analysis_task_outbox SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE analysis_id=?", id);
    }

    private UUID create(AnalysisType type) {
        UUID id = service.createResolved(new CreateAnalysisRequest("file", "key", type, null), "alice").id();
        return id;
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
