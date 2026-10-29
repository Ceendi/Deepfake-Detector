package com.deepfake.orchestrator.listener;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.deepfake.orchestrator.config.*;
import com.deepfake.orchestrator.service.AnalysisService;

/** Actual broker routing returns, publisher nacks and live-TCP confirm timeouts with production factories. */
@Testcontainers
@SpringBootTest(classes = AmqpFailurePolicyIntegrationTest.Config.class, properties = {
        "spring.rabbitmq.listener.simple.retry.max-retries=2",
        "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
        "spring.rabbitmq.listener.simple.retry.multiplier=2",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AmqpFailurePolicyIntegrationTest {
    @Container static final GenericContainer<?> broker = new GenericContainer<>("rabbitmq:4.3.6-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", broker::getHost);
        registry.add("spring.rabbitmq.port", () -> broker.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test"); registry.add("spring.rabbitmq.password", () -> "test");
    }
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired CachingConnectionFactory connectionFactory;
    @Autowired AmqpRecoveryControl recovery;
    @Autowired RabbitListenerEndpointRegistry registry;
    @MockitoBean AnalysisService service;
    @MockitoSpyBean DeadLetterListener deadLetters;
    final AtomicInteger deliveries = new AtomicInteger();
    boolean brokerPaused;

    @BeforeEach void resetState() {
        reset(service, deadLetters); deliveries.set(0);
        for (String id : List.of(RabbitConfig.RESULTS_LISTENER, RabbitConfig.DLQ_LISTENER)) {
            recovery.succeeded(id); registry.getListenerContainer(id).start();
        }
        for (String queue : List.of(RabbitConfig.Q_RESULTS, RabbitConfig.Q_PROGRESS, RabbitConfig.Q_RESULTS_DLQ,
                RabbitConfig.Q_VIDEO_DLQ, RabbitConfig.Q_AUDIO_DLQ, RabbitConfig.Q_QUARANTINE)) admin.purgeQueue(queue);
        bindQuarantine();
        bindResultsDlq();
        doAnswer(invocation -> { deliveries.incrementAndGet(); return invocation.callRealMethod(); })
                .when(deadLetters).onDead(any(Message.class));
    }
    @AfterEach void restoreBroker() {
        if (brokerPaused) { broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); brokerPaused = false; }
        bindQuarantine();
        bindResultsDlq();
    }

    @Test void advisoryFailureIsDroppedWithoutAnyTerminalFailureOrRecoveryPublication() {
        doThrow(new DataAccessResourceFailureException("progress database error")).when(service).handleProgress(any());
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_PROGRESS, Map.of("analysis_id", UUID.randomUUID().toString(), "progress", 50));
        verify(service, timeout(5000).times(3)).handleProgress(any());
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(service, times(3)).handleProgress(any()));
        verify(service, never()).failFromDlq(any(), any(), any());
        assertThat(rabbit.receive(RabbitConfig.Q_RESULTS_DLQ, 100)).isNull();
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 100)).isNull();
    }

    @Test void transientDatabaseFailureIsRetriedBeforeOriginalIsAcknowledged() {
        UUID id = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("temporary"))
                .doNothing().when(service).failFromDlq(eq(id), eq("video"), any());
        publishDead(id);
        verify(service, timeout(5000).times(2)).failFromDlq(eq(id), eq("video"), any());
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 100)).isNull();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(count(RabbitConfig.Q_VIDEO_DLQ)).isZero());
    }

    @Test void exhaustedPermanentDataFailureAndPoisonBothEnterDurableQuarantine() {
        UUID id = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("invalid stored data")).when(service).failFromDlq(eq(id), eq("video"), any());
        publishDead(id);
        Message parked = rabbit.receive(RabbitConfig.Q_QUARANTINE, 5000);
        assertThat(parked).isNotNull();
        assertThat(parked.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        verify(service, times(3)).failFromDlq(eq(id), eq("video"), any());
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", "malformed"));
        Message poison = rabbit.receive(RabbitConfig.Q_QUARANTINE, 5000);
        assertThat(poison).isNotNull();
        assertThat(poison.getMessageProperties().getHeaders()).containsKey("x-exception-message");
        assertThat(deliveries).hasValue(4); // three DB attempts, one non-retryable poison
        verify(service, times(3)).failFromDlq(any(), any(), any());
    }

    @Test void mandatoryReturnPausesOnlyAffectedListenerRetainsOriginalAndAutomaticallyResumes() {
        unbindQuarantine();
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", "poison"));
        assertPausedWithOriginal();
        UUID healthy = UUID.randomUUID();
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS, Map.of("analysis_id", healthy.toString(), "source", "video"));
        verify(service, timeout(2000)).handleResult(argThat(payload -> healthy.toString().equals(payload.get("analysis_id"))));
        bindQuarantine();
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 15_000)).isNotNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recovery.health().getStatus()).isEqualTo(Status.UP));
        assertThat(deliveries.get()).isBetween(2, 3);
    }

    @Test void resultRecoveryReturnRetainsOriginalWhileProgressListenerRemainsHealthy() {
        Binding binding = new Binding(RabbitConfig.Q_RESULTS_DLQ, Binding.DestinationType.QUEUE,
                RabbitConfig.DLX, RabbitConfig.Q_RESULTS_DLQ, Map.of());
        admin.removeBinding(binding);
        UUID id = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("invalid result data")).when(service).handleResult(any());
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS,
                Map.of("analysis_id", id.toString(), "source", "video", "status", "FAILED"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(registry.getListenerContainer(RabbitConfig.RESULTS_LISTENER).isRunning()).isFalse();
            assertThat(count(RabbitConfig.Q_RESULTS)).isEqualTo(1);
        });
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> verify(service, times(3)).handleResult(any()));
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_PROGRESS, Map.of("analysis_id", id.toString(), "progress", 50));
        verify(service, timeout(2000)).handleProgress(any());
        admin.declareBinding(binding);
        verify(service, timeout(15_000)).failFromDlq(eq(id), eq("video"), any());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recovery.health().getStatus()).isEqualTo(Status.UP));
        verify(service, times(6)).handleResult(any());
    }

    @Test void extendedPublicationOutageKeepsDelayedRecoveryAutomaticBeyondInitialAttempt() {
        unbindQuarantine();
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", "poison"));
        assertPausedWithOriginal();
        // The original is retried after 5s and then 10s; no finite restart count strands it.
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(deliveries).hasValue(2));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(deliveries).hasValue(2);
            assertThat(recovery.health().getStatus()).isEqualTo(Status.DOWN);
        });
        bindQuarantine();
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 15_000)).isNotNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recovery.health().getStatus()).isEqualTo(Status.UP));
        assertThat(deliveries).hasValue(3);
    }

    @Test void actualBrokerNackAlsoRetainsOriginalUntilRecoveryCanBeConfirmed() {
        // Bind a separate test-only overflow queue to the production recovery routing key. No
        // arguments on shared detector queues or on the backend quarantine queue are changed.
        String overflow = "test.recovery.overflow";
        admin.declareQueue(QueueBuilder.durable(overflow).withArgument("x-max-length", 1)
                .withArgument("x-overflow", "reject-publish").build());
        rabbit.convertAndSend("", overflow, "occupy");
        Binding binding = new Binding(overflow, Binding.DestinationType.QUEUE, RabbitConfig.DLX, RabbitConfig.Q_QUARANTINE, Map.of());
        admin.declareBinding(binding);
        try {
            rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", "poison"));
            assertPausedWithOriginal();
            admin.removeBinding(binding);
            // A nack can still have copied to another routed queue. Recovery is at-least-once.
            admin.purgeQueue(RabbitConfig.Q_QUARANTINE);
            assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 15_000)).isNotNull();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recovery.health().getStatus()).isEqualTo(Status.UP));
            assertThat(deliveries.get()).isBetween(2, 3);
        } finally { admin.removeBinding(binding); admin.deleteQueue(overflow); }
    }

    @Test void pausedLiveTcpBrokerBoundsConfirmationAndShutdownThenRequeuesAfterUnpause() throws Exception {
        assertThat(connectionFactory.getRabbitConnectionFactory().getChannelRpcTimeout()).isEqualTo(1000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            deliveries.incrementAndGet(); entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier expired");
            throw new PoisonDeadLetterException("poison", new IllegalArgumentException());
        }).when(deadLetters).onDead(any(Message.class));
        // Warm the actual correlated publisher and listener channels before pausing the real broker.
        rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", "poison"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec(); brokerPaused = true;
        long started = System.nanoTime(); release.countDown();
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(recovery.health().getStatus()).isEqualTo(Status.DOWN);
                assertThat(registry.getListenerContainer(RabbitConfig.DLQ_LISTENER).isRunning()).isFalse();
            });
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(deliveries).hasValue(1));
        } finally {
            broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); brokerPaused = false;
            // Let the next original delivery reach the real poison parser after transport recovery.
            doAnswer(invocation -> { deliveries.incrementAndGet(); return invocation.callRealMethod(); })
                    .when(deadLetters).onDead(any(Message.class));
        }
        assertThat(rabbit.receive(RabbitConfig.Q_QUARANTINE, 15_000)).isNotNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(recovery.health().getStatus()).isEqualTo(Status.UP));
        assertThat(deliveries.get()).isBetween(2, 3);
    }

    private void publishDead(UUID id) { rabbit.convertAndSend(RabbitConfig.DLX, RabbitConfig.Q_VIDEO_DLQ, Map.of("analysis_id", id.toString())); }
    private void assertPausedWithOriginal() {
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
            assertThat(recovery.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(registry.getListenerContainer(RabbitConfig.DLQ_LISTENER).isRunning()).isFalse();
            assertThat(count(RabbitConfig.Q_VIDEO_DLQ)).isEqualTo(1);
        });
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(deliveries).hasValue(1));
    }
    private long count(String queue) { return admin.getQueueInfo(queue).getMessageCount(); }
    private void bindResultsDlq() { admin.declareBinding(new Binding(RabbitConfig.Q_RESULTS_DLQ, Binding.DestinationType.QUEUE, RabbitConfig.DLX, RabbitConfig.Q_RESULTS_DLQ, Map.of())); }
    private void bindQuarantine() { admin.declareBinding(new Binding(RabbitConfig.Q_QUARANTINE, Binding.DestinationType.QUEUE, RabbitConfig.DLX, RabbitConfig.Q_QUARANTINE, Map.of())); }
    private void unbindQuarantine() { admin.removeBinding(new Binding(RabbitConfig.Q_QUARANTINE, Binding.DestinationType.QUEUE, RabbitConfig.DLX, RabbitConfig.Q_QUARANTINE, Map.of())); }
    @Configuration
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitConfig.class, AnalysisResultListener.class, DeadLetterListener.class})
    static class Config { }
}
