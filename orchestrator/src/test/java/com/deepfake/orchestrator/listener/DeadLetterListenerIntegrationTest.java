package com.deepfake.orchestrator.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.service.AnalysisService;

/**
 * End-to-end on a live broker: a failing result is retried, republished to analysis.results.dlq, and
 * the manual-ack DLQ consumer drains it and marks the analysis FAILED. Proves retry -> recoverer ->
 * DLQ -> manual ack all wire up on the Boot 4.0 factory. Needs Docker.
 */
@Testcontainers
@SpringBootTest(
        classes = DeadLetterListenerIntegrationTest.TestConfig.class,
        properties = {
                "spring.rabbitmq.listener.simple.acknowledge-mode=auto",
                "spring.rabbitmq.listener.simple.retry.enabled=true",
                "spring.rabbitmq.listener.simple.retry.max-attempts=3",
                "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
                "spring.rabbitmq.listener.simple.retry.multiplier=2",
                "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
                "eureka.client.enabled=false"
        })
class DeadLetterListenerIntegrationTest {

    @Container
    static final GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:4.3.1-alpine"))
                    .withEnv("RABBITMQ_DEFAULT_USER", "test")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "test")
                    .withExposedPorts(5672)
                    // Port-wait alone is not enough: Docker Desktop accepts TCP on the mapped port
                    // before the broker is up, so early AMQP handshakes fail (flaky on Windows).
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

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
    void deadLetteredResultIsConsumedAndFailed() {
        doThrow(new RuntimeException("boom")).when(analysisService).handleResult(any());

        UUID id = UUID.randomUUID();
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS,
                Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED"));

        verify(analysisService, timeout(15_000)).failFromDlq(eq(id), any());
        assertThat(rabbitTemplate.receive(RabbitConfig.Q_RESULTS_DLQ, 500))
                .as("DLQ consumer should have acked and drained the message").isNull();
    }

    @Configuration
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitConfig.class, AnalysisResultListener.class, DeadLetterListener.class})
    static class TestConfig {
    }
}
