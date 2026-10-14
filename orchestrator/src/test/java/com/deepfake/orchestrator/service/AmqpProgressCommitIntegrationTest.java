package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Duration;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.config.AmqpRecoveryControl;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.health.contributor.Status;
import com.deepfake.orchestrator.entity.*;
import com.deepfake.orchestrator.listener.*;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** Deferred PostgreSQL commit faults through real AUTO Rabbit listeners and the production retry factories. */
@DataJpaTest(showSql = false, properties = {
        "spring.rabbitmq.listener.simple.retry.max-retries=2",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=2",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
@Import({AnalysisService.class, RabbitConfig.class, AnalysisResultListener.class, DeadLetterListener.class,
        AmqpProgressCommitIntegrationTest.RedisConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AmqpProgressCommitIntegrationTest {
    private static final int DATABASE_PORT = availablePort();
    // Docker can reassign ephemeral published ports on restart; the JDBC endpoint must stay stable.
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(DATABASE_PORT), new ExposedPort(5432))));
    @Container static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0-alpine").withExposedPorts(6379);
    @Container static final GenericContainer<?> broker = new GenericContainer<>("rabbitmq:4.3.1-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", broker::getHost);
        registry.add("spring.rabbitmq.port", () -> broker.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test"); registry.add("spring.rabbitmq.password", () -> "test");
    }
    @Autowired AnalysisService service;
    @Autowired AnalysisRepository repository;
    @Autowired RabbitTemplate rabbit;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AmqpRecoveryControl recovery;
    @Autowired RabbitAdmin admin;
    @MockitoBean BackpressureGuard backpressure;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean AnalysisMetrics metrics;
    @MockitoSpyBean AnalysisResultListener results;
    @MockitoSpyBean DeadLetterListener deadLetters;
    final AtomicInteger progressAttempts = new AtomicInteger(), deadAttempts = new AtomicInteger();

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot allocate PostgreSQL test port", failure);
        }
    }

    @BeforeEach void resetState() {
        dropFault(); repository.deleteAll(); reset(cache, streams, results, deadLetters);
        progressAttempts.set(0); deadAttempts.set(0);
        doAnswer(invocation -> { progressAttempts.incrementAndGet(); return invocation.callRealMethod(); }).when(results).onProgress(any());
        doAnswer(invocation -> { deadAttempts.incrementAndGet(); return invocation.callRealMethod(); }).when(deadLetters).onDead(any());
        rabbit.execute(channel -> {
            for (String queue : new String[] {RabbitConfig.Q_RESULTS, RabbitConfig.Q_PROGRESS, RabbitConfig.Q_RESULTS_DLQ,
                    RabbitConfig.Q_VIDEO_DLQ, RabbitConfig.Q_AUDIO_DLQ, RabbitConfig.Q_QUARANTINE}) channel.queuePurge(queue);
            return null;
        });
    }
    @AfterEach void removeFault() { dropFault(); }

    @Test void stoppedPostgresKeepsResultsAndDeadLettersUntilDatabaseReturns() {
        UUID resultId = seed(AnalysisType.VIDEO), deadId = seed(AnalysisType.AUDIO);
        postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).withTimeout(1).exec();
        try {
            complete(resultId, "video");
            rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_AUDIO_DLQ, Map.of("analysis_id", deadId.toString()));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(recovery.health().getStatus()).isEqualTo(Status.DOWN));
            // Exceeds the old finite retry window, including connection acquisition timeouts.
            await().during(Duration.ofSeconds(42)).atMost(Duration.ofSeconds(48)).untilAsserted(() -> {
                assertThat(admin.getQueueInfo(RabbitConfig.Q_RESULTS_DLQ).getMessageCount()).isZero();
                assertThat(admin.getQueueInfo(RabbitConfig.Q_QUARANTINE).getMessageCount()).isZero();
            });
        } finally {
            postgres.getDockerClient().startContainerCmd(postgres.getContainerId()).exec();
            await().ignoreExceptions().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject("SELECT 1", Integer.class) == 1);
        }
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(status(resultId)).isEqualTo("COMPLETED");
            assertThat(status(deadId)).isEqualTo("FAILED");
            assertThat(recovery.health().getStatus()).isEqualTo(Status.UP);
        });
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 100)).isNull();
        verify(streams, timeout(2000).times(1)).sendResult(eq(resultId), any());
        verify(streams, timeout(2000).times(1)).sendResult(eq(deadId), any());
    }

    @Test void missingResultBypassesRetryFailsSourceAndParksCopy() {
        UUID id = seed(AnalysisType.VIDEO);
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS,
                Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED"));
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 5000)).isNotNull();
        assertThat(status(id)).isEqualTo("FAILED");
        verify(results, times(1)).onResult(any());
        assertThat(deadAttempts).hasValue(1);
    }

    @Test void progressCommitFailureDropsOnlyAdvisoryEventAndLaterRealResultCompletes() {
        UUID id = seed(AnalysisType.VIDEO);
        installFault("IF NEW.status = 'PROCESSING' THEN RAISE EXCEPTION 'deferred progress commit fault'; END IF;");
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_PROGRESS, progress(id));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(progressAttempts).hasValue(3));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(progressAttempts).hasValue(3));
        assertThat(status(id)).isEqualTo("PENDING");
        verifyNoInteractions(cache, streams);
        assertThat(redis.opsForValue().get("progress:" + id)).isNull();
        assertThat(rabbit.receive(RabbitConfig.Q_RESULTS_DLQ, 100)).isNull();
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 100)).isNull();
        dropFault(); complete(id, "video");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status(id)).isEqualTo("COMPLETED"));
        verify(streams, timeout(2000)).sendResult(eq(id), any());
        verify(streams, never()).sendProgress(eq(id), any());
    }

    @Test void rollbackOnlyTransactionDoesNotPublishProgress() {
        UUID id = seed(AnalysisType.VIDEO);
        var outer = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            service.handleProgress(progress(id));
            new TransactionTemplate(transactions).executeWithoutResult(inner -> inner.setRollbackOnly());
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(status(id)).isEqualTo("PENDING");
        verifyNoInteractions(cache, streams);
        assertThat(redis.opsForValue().get("progress:" + id)).isNull();
    }

    @Test void successfulProgressPublishesOnlyAfterCommittedProcessingStatus() {
        UUID id = seed(AnalysisType.VIDEO);
        doAnswer(invocation -> { assertThat(status(id)).isEqualTo("PROCESSING"); return null; })
                .when(streams).sendProgress(eq(id), any());
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_PROGRESS, progress(id));
        verify(streams, timeout(5000)).sendProgress(eq(id), any());
        assertThat(redis.opsForValue().get("progress:" + id)).isNull();
    }

    @Test void transientTerminalCommitFailureRetriesOriginalUntilItCommits() {
        UUID id = seed(AnalysisType.VIDEO);
        // PostgreSQL sequences are not rolled back, allowing deterministic transient commit faults.
        installFault("IF NEW.status = 'FAILED' AND nextval('amqp_failure_attempts') <= 2 THEN RAISE EXCEPTION 'transient terminal commit fault'; END IF;");
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", id.toString()));
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(status(id)).isEqualTo("FAILED"));
        assertThat(deadAttempts).hasValue(3);
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 100)).isNull();
        verify(streams, timeout(2000).times(1)).sendResult(eq(id), any());
    }

    @Test void exhaustedTerminalCommitFailureIsDurablyParkedAndAnalysisRemainsActive() {
        UUID id = seed(AnalysisType.VIDEO);
        installFault("IF NEW.status = 'FAILED' THEN RAISE EXCEPTION 'persistent terminal commit fault'; END IF;");
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", id.toString()));
        Message parked = rabbit.receive(RabbitConfig.Q_QUARANTINE, 8000);
        assertThat(parked).isNotNull();
        assertThat(parked.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(deadAttempts).hasValue(3);
        assertThat(status(id)).isEqualTo("PENDING");
        verifyNoInteractions(streams);
    }

    @Test void legacyProgressInResultsDlqIsQuarantinedAndValidResultStillCompletes() {
        UUID id = seed(AnalysisType.VIDEO);
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_RESULTS_DLQ, progress(id), message -> {
            message.getMessageProperties().setHeader("x-original-routingKey", RabbitConfig.Q_PROGRESS);
            return message;
        });
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 5000)).isNotNull();
        assertThat(status(id)).isEqualTo("PENDING"); verifyNoInteractions(streams);
        complete(id, "video");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status(id)).isEqualTo("COMPLETED"));
    }

    @Test void acceptedFullSourceAndUnexpectedOrCancelledSourcesCannotFailWholeAnalysis() {
        UUID full = seed(AnalysisType.FULL);
        service.handleResult(result(full, "video"));
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", full.toString()));
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_RESULTS_DLQ, result(full, "video"));
        UUID audio = seed(AnalysisType.AUDIO);
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", audio.toString()));
        UUID cancelled = seed(AnalysisType.VIDEO);
        jdbc.update("UPDATE analysis SET status='CANCELLED' WHERE id=?", cancelled);
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", cancelled.toString()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(deadAttempts).hasValue(4));
        assertThat(status(full)).isEqualTo("PENDING"); assertThat(status(audio)).isEqualTo("PENDING");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT video_prob FROM analysis WHERE id=?", java.math.BigDecimal.class, full)).isEqualByComparingTo("0.8");
        complete(full, "audio");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(status(full)).isEqualTo("COMPLETED"));
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 100)).isNull();
    }

    private UUID seed(AnalysisType type) { return repository.saveAndFlush(Analysis.builder().userId("alice").fileId("f").fileKey("k").type(type).build()).getId(); }
    private String status(UUID id) { return jdbc.queryForObject("SELECT status FROM analysis WHERE id=?", String.class, id); }
    private Map<String, Object> progress(UUID id) { return Map.of("analysis_id", id.toString(), "source", "video", "progress", 42, "stage", "INFERENCE"); }
    private Map<String, Object> result(UUID id, String source) { return Map.of("analysis_id", id.toString(), "source", source, "status", "COMPLETED", "result", Map.of("prob_fake", 0.8)); }
    private void complete(UUID id, String source) { rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS, result(id, source)); }
    private void installFault(String body) {
        jdbc.execute("CREATE SEQUENCE amqp_failure_attempts");
        jdbc.execute("CREATE FUNCTION amqp_commit_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " + body + " RETURN NEW; END; $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER amqp_commit_fault AFTER UPDATE ON analysis DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION amqp_commit_fault()");
    }
    private void dropFault() {
        jdbc.execute("DROP TRIGGER IF EXISTS amqp_commit_fault ON analysis");
        jdbc.execute("DROP FUNCTION IF EXISTS amqp_commit_fault()");
        jdbc.execute("DROP SEQUENCE IF EXISTS amqp_failure_attempts");
    }
    @TestConfiguration static class RedisConfig {
        @Bean LettuceConnectionFactory redisFactory() { return new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379)); }
        @Bean StringRedisTemplate redisTemplate(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
    }
}
