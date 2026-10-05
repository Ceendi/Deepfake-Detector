package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.config.RabbitConfig;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once dispatcher. Database row locks serialize publication with terminal transitions. */
@Slf4j
@Service
@ConditionalOnProperty(name = "reliability.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class TaskOutboxPublisher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final long confirmTimeoutMs;
    private final long retrySeconds;
    private final long dispatchTimeoutSeconds;
    private final int batchSize;

    public TaskOutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, PlatformTransactionManager manager,
            @Value("${reliability.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${reliability.outbox.retry-seconds:5}") long retrySeconds,
            @Value("${reliability.outbox.dispatch-timeout-seconds:120}") long dispatchTimeoutSeconds,
            @Value("${reliability.outbox.batch-size:20}") int batchSize) {
        if (confirmTimeoutMs < 1 || retrySeconds < 1 || retrySeconds > 60
                || dispatchTimeoutSeconds < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Outbox limits must be positive; retry must not exceed 60 seconds");
        }
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.retrySeconds = retrySeconds;
        this.dispatchTimeoutSeconds = dispatchTimeoutSeconds;
        this.batchSize = batchSize;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${reliability.outbox.poll-interval-ms:1000}")
    public void dispatchDue() {
        // Ordinary committed reads; no dirty visibility of a create that may still roll back.
        var tasks = transaction.execute(status -> jdbc.query("""
                SELECT o.id, o.analysis_id FROM analysis_task_outbox o JOIN analysis a ON a.id = o.analysis_id
                WHERE o.sent_at IS NULL AND o.next_attempt_at <= CURRENT_TIMESTAMP
                    AND a.status IN ('PENDING', 'PROCESSING') AND a.created_at >= ?
                ORDER BY o.next_attempt_at, o.created_at, o.id LIMIT ?
                """, (rs, row) -> new Task(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                Timestamp.from(Instant.now().minusSeconds(dispatchTimeoutSeconds)), batchSize));
        for (Task task : tasks) {
            try {
                transaction.executeWithoutResult(status -> dispatch(task));
            } catch (Exception failure) {
                // A failed database commit leaves a task unsent, even if the broker confirmed it.
                log.warn("Outbox transaction failed for task {}; it remains retryable", task.id(), failure);
            }
        }
    }

    private void dispatch(Task task) {
        // Lock analysis before outbox (also the delete/cascade order). Cancel, results and recovery
        // update this same row. If they committed first, no message can leave afterwards.
        var analyses = jdbc.query("""
                SELECT status, created_at FROM analysis WHERE id = ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new AnalysisState(rs.getString(1), rs.getTimestamp(2).toInstant()), task.analysisId());
        if (analyses.isEmpty()) return;
        var analysis = analyses.getFirst();
        if (!("PENDING".equals(analysis.status()) || "PROCESSING".equals(analysis.status()))
                || analysis.createdAt().isBefore(Instant.now().minusSeconds(dispatchTimeoutSeconds))) return;

        var rows = jdbc.query("""
                SELECT source, payload::text, attempts FROM analysis_task_outbox
                WHERE id = ? AND sent_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP FOR UPDATE
                """, (rs, row) -> new Pending(rs.getString(1), rs.getString(2), rs.getInt(3)), task.id());
        if (rows.isEmpty()) return;
        Pending pending = rows.getFirst();
        // Persist bounded exponential retry on normal failure. A killed transaction rolls back;
        // restart can retry once immediately, then continues at the configured polling cadence.
        int attempts = pending.attempts() + 1;
        long delay = Math.min(60, retrySeconds * (1L << Math.min(pending.attempts(), 6)));
        jdbc.update("UPDATE analysis_task_outbox SET attempts = ?, next_attempt_at = ? WHERE id = ?",
                attempts, Timestamp.from(Instant.now().plusSeconds(delay)), task.id());
        try {
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setMessageId(task.id().toString());
            CorrelationData correlation = new CorrelationData(task.id() + ":" + attempts);
            rabbit.send(RabbitConfig.EXCHANGE, "analysis." + pending.source(),
                    new Message(pending.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);
            var confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            // Spring associates mandatory returns before completing the correlated confirm future.
            if (!confirm.ack() || correlation.getReturned() != null) {
                log.warn("Outbox task {} was nacked or returned; retry scheduled", task.id());
                return;
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Outbox publication failed for task {}; retry scheduled", task.id(), failure);
            return;
        }
        // Deliberately outside the publish catch: a database failure must roll back, never turn a
        // confirmed message into a falsely committed sent marker. Crash here permits duplicates.
        jdbc.update("UPDATE analysis_task_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", task.id());
        jdbc.update("UPDATE analysis SET updated_at = GREATEST(updated_at, CURRENT_TIMESTAMP) WHERE id = ?",
                task.analysisId());
    }

    private record Task(UUID id, UUID analysisId) {}
    private record Pending(String source, String payload, int attempts) {}
    private record AnalysisState(String status, Instant createdAt) {}
}
