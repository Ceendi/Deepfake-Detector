package com.deepfake.orchestrator.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
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
import com.deepfake.orchestrator.metrics.ListenerRetryMetricsCustomizer;
import com.deepfake.orchestrator.service.AnalysisService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A handler that keeps throwing is retried, then republished to analysis.results.dlq and acked
 * (no infinite redelivery). Verifies retry + recoverer auto-wire on the Boot 4.0 factory. Needs Docker.
 */
@Testcontainers
@SpringBootTest(
        classes = AnalysisResultRetryIntegrationTest.TestConfig.class,
        properties = {
                "spring.rabbitmq.listener.simple.acknowledge-mode=auto",
                "spring.rabbitmq.listener.simple.retry.enabled=true",
                "spring.rabbitmq.listener.simple.retry.max-retries=2",
                // Tiny intervals so the test isn't gated on the production 1s/4s/15s backoff.
                "spring.rabbitmq.listener.simple.retry.initial-interval=50ms",
                "spring.rabbitmq.listener.simple.retry.multiplier=2",
                "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
                "eureka.client.enabled=false"
        })
class AnalysisResultRetryIntegrationTest {

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

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    AnalysisService analysisService;

    @Test
    void exhaustedRetriesLandInResultsDlq() {
        doThrow(new RuntimeException("boom")).when(analysisService).handleResult(any());

        UUID id = UUID.randomUUID();
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_RESULTS,
                Map.of("analysis_id", id.toString(), "source", "video", "status", "COMPLETED"));

        Message dead = rabbitTemplate.receive(RabbitConfig.Q_RESULTS_DLQ, 15_000);

        assertThat(dead).as("failed result should be republished to the DLQ").isNotNull();
        assertThat(dead.getMessageProperties().getHeaders()).containsKey("x-exception-message");
        // original was acked after republish — nothing loops back
        assertThat(rabbitTemplate.receive(RabbitConfig.Q_RESULTS, 200)).isNull();
        verify(analysisService, atLeast(3)).handleResult(any());
        // max-retries=2 = 1 initial + 2 redeliveries -> amqp_listener_retries_total must be exactly 2,
        // pinning the counter's semantics (retries, not failed attempts) against the real Boot factory.
        assertThat(meterRegistry.get("amqp.listener.retries").counter().count()).isEqualTo(2.0);
    }

    @Configuration
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({RabbitConfig.class, AnalysisResultListener.class, ListenerRetryMetricsCustomizer.class})
    static class TestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
