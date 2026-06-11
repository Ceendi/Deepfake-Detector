package com.deepfake.orchestrator.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.service.AnalysisService;

/**
 * D6 resilience: a broker restart must not require an app restart. The broker process is bounced
 * via rabbitmqctl stop_app/start_app (container and mapped port survive, all AMQP connections
 * drop), then both sides must self-heal: RabbitTemplate opens a fresh connection on the next
 * publish, the listener container reconnects on its recovery loop and consumes the new message.
 * Durable queues/bindings persist across the bounce. Needs Docker.
 */
@Testcontainers
@SpringBootTest(
        classes = RabbitRestartReconnectIntegrationTest.TestConfig.class,
        properties = {"eureka.client.enabled=false"})
class RabbitRestartReconnectIntegrationTest {

    @Container
    static final GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.1-alpine"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "test")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "test")
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void rabbitProps(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "test");
        registry.add("spring.rabbitmq.password", () -> "test");
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    @MockitoBean
    AnalysisService analysisService;

    @Test
    void listenerAndPublisherRecoverAfterBrokerRestart() throws Exception {
        CountDownLatch firstDelivery = new CountDownLatch(1);
        CountDownLatch secondDelivery = new CountDownLatch(2);
        doAnswer(inv -> {
            firstDelivery.countDown();
            secondDelivery.countDown();
            return null;
        }).when(analysisService).handleResult(any());

        sendResult();
        assertThat(firstDelivery.await(15, TimeUnit.SECONDS))
                .as("baseline delivery before the restart").isTrue();

        rabbit.execInContainer("rabbitmqctl", "stop_app");
        rabbit.execInContainer("rabbitmqctl", "start_app");

        sendResultRetrying(Duration.ofSeconds(60));

        assertThat(secondDelivery.await(30, TimeUnit.SECONDS))
                .as("listener should reconnect and consume after the broker restart").isTrue();
    }

    private void sendResult() {
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS,
                Map.of("analysis_id", UUID.randomUUID().toString(),
                        "source", "video", "status", "COMPLETED"));
    }

    // Publishing right after start_app can still hit a dead cached connection; keep trying until
    // the template re-establishes one (that lazy re-open IS the recovery behaviour under test).
    private void sendResultRetrying(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                sendResult();
                return;
            } catch (AmqpException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }

    @Configuration
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitConfig.class, AnalysisResultListener.class})
    static class TestConfig {
    }
}
