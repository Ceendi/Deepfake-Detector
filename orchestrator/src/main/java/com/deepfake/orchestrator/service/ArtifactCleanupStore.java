package com.deepfake.orchestrator.service;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database operations only. Storage calls never run inside these transactions. */
@Service
public class ArtifactCleanupStore {
    public record Work(String key, UUID analysisId, UUID token, int attempts) {}
    public record Scan(UUID token, String continuationToken) {}
    public record Candidate(String key, UUID analysisId) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration lease;
    private final Duration retry;
    private final Duration maxRetry;

    public ArtifactCleanupStore(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${reliability.artifact-cleanup.lease:30s}") Duration lease,
            @Value("${reliability.artifact-cleanup.retry:5s}") Duration retry,
            @Value("${reliability.artifact-cleanup.max-retry:1h}") Duration maxRetry,
            @Value("${storage.artifact-call-timeout:5s}") Duration callTimeout) {
        if (lease.compareTo(callTimeout.plusSeconds(1)) <= 0 || retry.toMillis() < 1
                || maxRetry.compareTo(retry) < 0) {
            throw new IllegalArgumentException("Cleanup lease must exceed the storage budget plus 1s; retry budgets must be positive");
        }
        this.jdbc = jdbc;
        this.lease = lease;
        this.retry = retry;
        this.maxRetry = maxRetry;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }

    /** Join the analysis deletion transaction, never commit work independently of its parent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueDeletion(UUID analysisId, Collection<String> keys) {
        keys.stream().distinct().sorted().forEach(key -> insert(new Candidate(key, analysisId)));
    }

    private void insert(Candidate candidate) {
        // A duplicate must retain the tuple lock until the enclosing deletion commits. Otherwise a
        // claimant can discard still-referenced work while the last reference is being deleted.
        // The no-op update preserves the original owner, retry schedule, attempts and lease.
        jdbc.update("INSERT INTO artifact_cleanup (object_key, analysis_id) VALUES (?, ?) "
                + "ON CONFLICT (object_key) DO UPDATE SET object_key = EXCLUDED.object_key",
                candidate.key(), candidate.analysisId());
    }

    public Work claim() {
        return tx.execute(status -> {
            List<Work> rows = jdbc.query("""
                    SELECT object_key, analysis_id, attempts FROM artifact_cleanup
                    WHERE next_attempt_at <= CURRENT_TIMESTAMP
                      AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, created_at, object_key LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, n) -> new Work(rs.getString(1), rs.getObject(2, UUID.class), UUID.randomUUID(), rs.getInt(3)));
            if (rows.isEmpty()) return null;
            Work work = rows.getFirst();
            // Active analyses may still accept new references. Terminal state cannot become active;
            // after deletion, analysis UUIDs are not reused. Also protect references on ANY analysis.
            if (protectedObject(work.analysisId(), work.key())) {
                jdbc.update("DELETE FROM artifact_cleanup WHERE object_key = ?", work.key());
                return new Work(work.key(), work.analysisId(), null, work.attempts());
            }
            jdbc.update("UPDATE artifact_cleanup SET lease_token = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') WHERE object_key = ?",
                    work.token(), lease.toMillis(), work.key());
            return work;
        });
    }

    private boolean protectedObject(UUID analysisId, String key) {
        UUID keyAnalysisId = analysisId;
        if (key.length() > 36 && key.charAt(36) == '/') {
            try {
                UUID prefix = UUID.fromString(key.substring(0, 36));
                if (prefix.toString().equalsIgnoreCase(key.substring(0, 36))) keyAnalysisId = prefix;
            } catch (IllegalArgumentException ignored) { /* Legacy non-UUID names still protect the recorded owner. */ }
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM analysis WHERE id IN (?, ?) AND status IN ('PENDING', 'PROCESSING'))
                    OR EXISTS (SELECT 1 FROM analysis
                      WHERE video_details->'gradcamKeys' @> jsonb_build_array(CAST(? AS TEXT))
                         OR audio_details->'gradcamKeys' @> jsonb_build_array(CAST(? AS TEXT)))
                """, Boolean.class, analysisId, keyAnalysisId, key, key));
    }

    public void complete(Work work) {
        tx.executeWithoutResult(status -> jdbc.update("DELETE FROM artifact_cleanup WHERE object_key = ? AND lease_token = ?", work.key(), work.token()));
    }

    public void retry(Work work) {
        long delay = retry.toMillis();
        for (int i = 0; i < Math.min(work.attempts(), 30) && delay < maxRetry.toMillis(); i++) {
            delay = delay > maxRetry.toMillis() / 2 ? maxRetry.toMillis() : delay * 2;
        }
        long retryMillis = Math.min(delay, maxRetry.toMillis());
        tx.executeWithoutResult(status -> jdbc.update("""
                UPDATE artifact_cleanup SET attempts = attempts + 1,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), lease_token = NULL, lease_until = NULL
                WHERE object_key = ? AND lease_token = ?
                """, retryMillis, work.key(), work.token()));
    }

    public Scan claimScan() {
        return tx.execute(status -> {
            List<Scan> rows = jdbc.query("""
                    SELECT continuation_token FROM artifact_cleanup_scan WHERE id = 1
                    AND (lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP) FOR UPDATE SKIP LOCKED
                    """, (rs, n) -> new Scan(UUID.randomUUID(), rs.getString(1)));
            if (rows.isEmpty()) return null;
            Scan scan = rows.getFirst();
            jdbc.update("UPDATE artifact_cleanup_scan SET lease_token = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond') WHERE id = 1", scan.token(), lease.toMillis());
            return scan;
        });
    }

    public void finishScan(Scan scan, Collection<Candidate> candidates, String nextToken) {
        tx.executeWithoutResult(status -> {
            List<Integer> owned = jdbc.query("SELECT id FROM artifact_cleanup_scan WHERE id = 1 AND lease_token = ? FOR UPDATE", (rs, n) -> rs.getInt(1), scan.token());
            if (owned.isEmpty()) return;
            // Match deletion enqueue order and deduplicate by key before acquiring shared row locks.
            var ordered = new TreeMap<String, Candidate>();
            candidates.forEach(candidate -> ordered.putIfAbsent(candidate.key(), candidate));
            for (Candidate candidate : ordered.values()) {
                if (!protectedObject(candidate.analysisId(), candidate.key())) insert(candidate);
            }
            jdbc.update("UPDATE artifact_cleanup_scan SET continuation_token = ?, lease_token = NULL, lease_until = NULL WHERE id = 1", nextToken);
        });
    }

    public void releaseScan(Scan scan) {
        tx.executeWithoutResult(status -> jdbc.update("UPDATE artifact_cleanup_scan SET lease_token = NULL, lease_until = NULL WHERE id = 1 AND lease_token = ?", scan.token()));
    }
}
