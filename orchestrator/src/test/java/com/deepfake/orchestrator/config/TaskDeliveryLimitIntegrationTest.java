package com.deepfake.orchestrator.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TaskDeliveryLimitIntegrationTest {
    @Container
    static final GenericContainer<?> broker = new GenericContainer<>("rabbitmq:4.3.6-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", "test").withEnv("RABBITMQ_DEFAULT_PASS", "test")
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @Test
    void connectionLossCountsAsFailureOnRabbit43() throws Exception {
        verifyLimit("proof.task", Map.of("x-queue-type", "quorum", "x-delivery-limit", 5,
                "x-dead-letter-exchange", "analysis.dlx", "x-dead-letter-routing-key", "proof.task.dlq"));
    }

    @Test
    void productionTaskQueuesBoundConnectionLossRedelivery() throws Exception {
        var config = new RabbitConfig();
        for (Queue queue : List.of(config.videoQueue(), config.audioQueue())) {
            assertThat(queue.getArguments()).containsEntry("x-queue-type", "quorum")
                    .containsEntry("x-delivery-limit", 5);
            verifyLimit(queue.getName(), queue.getArguments());
        }
    }

    private void verifyLimit(String queue, Map<String, Object> arguments) throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost(broker.getHost());
        factory.setPort(broker.getMappedPort(5672));
        factory.setUsername("test");
        factory.setPassword("test");
        factory.setAutomaticRecoveryEnabled(false);
        byte[] body = "poison".getBytes(StandardCharsets.UTF_8);
        try (var publisher = factory.newConnection(); var channel = publisher.createChannel()) {
            channel.exchangeDeclare("analysis.dlx", "direct", true);
            channel.queueDeclare(queue + ".dlq", true, false, false, null);
            channel.queueBind(queue + ".dlq", "analysis.dlx", queue + ".dlq");
            channel.queueDeclare(queue, true, false, false, arguments);
            channel.confirmSelect();
            channel.basicPublish("", queue, new AMQP.BasicProperties.Builder().deliveryMode(2).build(), body);
            channel.waitForConfirmsOrDie(5000);
            for (int failures = 0; failures <= 5; failures++) {
                var consumer = factory.newConnection();
                try {
                    var consumingChannel = consumer.createChannel();
                    consumingChannel.basicQos(1);
                    var deliveries = new LinkedBlockingQueue<Delivery>();
                    consumingChannel.basicConsume(queue, false, (tag, delivery) -> deliveries.add(delivery), tag -> {});
                    Delivery delivery = deliveries.poll(10, TimeUnit.SECONDS);
                    assertThat(delivery).as("delivery after %s connection failures", failures).isNotNull();
                    assertThat(delivery.getBody()).isEqualTo(body);
                    var headers = delivery.getProperties().getHeaders();
                    Number count = headers == null ? 0L : (Number) headers.getOrDefault("x-delivery-count", 0L);
                    assertThat(count.longValue()).isEqualTo(failures);
                } finally {
                    // No ACK, reject or nack: close the transport with an outstanding delivery.
                    consumer.abort();
                }
            }
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var dead = channel.basicGet(queue + ".dlq", true);
                assertThat(dead).isNotNull();
                assertThat(dead.getBody()).isEqualTo(body);
                assertThat(dead.getProps().getHeaders().get("x-first-death-reason").toString()).isEqualTo("delivery_limit");
            });
            assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero();
        }
    }
}
