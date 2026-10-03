package com.deepfake.orchestrator.service;

import java.sql.Connection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.deepfake.orchestrator.exception.TooManyAnalysesException;
import com.deepfake.orchestrator.repository.AnalysisRepository;

/**
 * Counts active database rows instead of maintaining a second, non-transactional counter.
 * A PostgreSQL transaction advisory lock serializes admissions until commit or rollback.
 * Terminal transitions free capacity only when their database update commits.
 */
@Component
public class BackpressureGuard {

    // Dedicated advisory-lock namespace and resource, shared by all admissions to this database.
    private static final String ADMISSION_LOCK = "SELECT pg_advisory_xact_lock(114545, 1)";

    private final JdbcTemplate jdbc;
    private final AnalysisRepository repository;
    private final int maxInflight;
    private final int retryAfterSeconds;

    public BackpressureGuard(JdbcTemplate jdbc, AnalysisRepository repository,
            @Value("${backpressure.max-inflight:20}") int maxInflight,
            @Value("${backpressure.retry-after-seconds:5}") int retryAfterSeconds) {
        if (maxInflight < 1 || retryAfterSeconds < 1) {
            throw new IllegalArgumentException("Backpressure limits must be positive");
        }
        this.jdbc = jdbc;
        this.repository = repository;
        this.maxInflight = maxInflight;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Must share the writer's transaction and connection; an autocommit lock offers no protection. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            // The count after waiting for the lock must see the preceding admission's commit.
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new IllegalStateException("Analysis admission requires READ_COMMITTED isolation");
            }
            try (var statement = connection.createStatement()) {
                statement.execute(ADMISSION_LOCK);
            }
            return null;
        });
        // Also count creates already made by a caller in the same enclosing transaction.
        repository.flush();
        long active = repository.countActive();
        if (active >= maxInflight) {
            int queuePosition = (int) Math.min(active + 1, Integer.MAX_VALUE);
            throw new TooManyAnalysesException(queuePosition, retryAfterSeconds);
        }
    }
}
