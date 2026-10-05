package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.OrchestratorApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Launches the real orchestrator in a separate JVM; only a post-confirm test barrier is added. */
public class OutboxProcessProbe {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{OrchestratorApplication.class, BarrierConfiguration.class}, args);
    }

    @TestConfiguration
    static class BarrierConfiguration {
        @Bean @Primary
        RabbitTemplate probeRabbitTemplate(ConnectionFactory factory, MessageConverter converter) {
            RabbitTemplate template = new RabbitTemplate(factory) {
                @Override public void send(String exchange, String routingKey, Message message, CorrelationData correlation) {
                    super.send(exchange, routingKey, message, correlation);
                    String marker = System.getenv("OUTBOX_CONFIRM_BARRIER");
                    if (marker == null) return;
                    try {
                        if (!correlation.getFuture().get(5, TimeUnit.SECONDS).ack() || correlation.getReturned() != null) {
                            throw new IllegalStateException("Probe requires a real routed broker ACK");
                        }
                        Files.writeString(Path.of(marker), message.getMessageProperties().getMessageId());
                        // Parent sends SIGKILL while the production publisher still owns the DB transaction.
                        Thread.sleep(60_000);
                        throw new IllegalStateException("Probe was not killed after its confirm barrier");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
            };
            template.setMessageConverter(converter);
            template.setMandatory(true);
            template.setObservationEnabled(true);
            return template;
        }
    }
}
