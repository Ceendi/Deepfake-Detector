package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.dto.sse.AnalysisStreamSnapshot;
import com.deepfake.orchestrator.dto.response.UserStats;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    // A scalar projection bypasses the persistence-context identity map. REQUIRES_NEW also
    // excludes the caller's uncommitted changes and any repeatable-read snapshot.
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            readOnly = true)
    @Query("""
            SELECT new com.deepfake.orchestrator.dto.sse.AnalysisStreamSnapshot(
                a.id, a.status, a.verdict, a.confidence)
            FROM Analysis a WHERE a.id = :id AND a.userId = :userId
            """)
    Optional<AnalysisStreamSnapshot> streamSnapshot(
            @Param("id") UUID id, @Param("userId") String userId);


    // Literal statuses allow PostgreSQL to use the existing partial active-analysis index.
    @Query(value = "SELECT COUNT(*) FROM analysis WHERE status IN ('PENDING', 'PROCESSING')", nativeQuery = true)
    long countActive();

    @Query(value = """
            SELECT COUNT(*) FROM analysis
            WHERE user_id = :userId AND status IN ('PENDING', 'PROCESSING')
            """, nativeQuery = true)
    long countActiveByUser(@Param("userId") String userId);

    Page<AnalysisSummary> findByUserId(String userId, Pageable pageable);

    // Atomic aggregation + terminal transitions. clearAutomatically so the caller
    // can re-read fresh; each returns rows touched (0 => already terminal/unknown).

    // First accepted success owns both probability and details. PostgreSQL rechecks the
    // predicate after waiting for a concurrent row update; the lock lasts until commit.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Analysis a SET a.videoProb = :prob, a.videoDetails = :details, a.updatedAt = :now
            WHERE a.id = :id AND a.status IN :active AND a.type IN (VIDEO, FULL)
                AND a.videoProb IS NULL
            """)
    int writeVideoProb(@Param("id") UUID id, @Param("prob") BigDecimal prob,
            @Param("details") Map<String, Object> details,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Analysis a SET a.audioProb = :prob, a.audioDetails = :details, a.updatedAt = :now
            WHERE a.id = :id AND a.status IN :active AND a.type IN (AUDIO, FULL)
                AND a.audioProb IS NULL AND a.audioDetails IS NULL
            """)
    int writeAudioProb(@Param("id") UUID id, @Param("prob") BigDecimal prob,
            @Param("details") Map<String, Object> details,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    // A first accepted no-speech outcome owns audio details without inventing a probability.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Analysis a SET a.audioDetails = :details, a.updatedAt = :now
            WHERE a.id = :id AND a.status IN :active AND a.type = FULL
                AND a.audioProb IS NULL AND a.audioDetails IS NULL
            """)
    int writeAudioNotApplicable(@Param("id") UUID id, @Param("details") Map<String, Object> details,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    // CAS the status: 1 row => this caller won the transition, so it runs the side effects exactly once.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.status = :to, a.verdict = :verdict, a.confidence = :confidence, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int complete(@Param("id") UUID id, @Param("to") AnalysisStatus to, @Param("verdict") String verdict,
            @Param("confidence") BigDecimal confidence, @Param("active") Collection<AnalysisStatus> active,
            @Param("now") Instant now);

    // Ordinary source failures are terminal only before that source has an accepted outcome.
    // Audio details also protect a durable FULL no-speech outcome from late failures/DLQs.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Analysis a SET a.status = :to, a.errorMessage = :msg, a.updatedAt = :now
            WHERE a.id = :id AND a.status IN :active AND (
                (:source = 'video' AND a.type IN (VIDEO, FULL) AND a.videoProb IS NULL) OR
                (:source = 'audio' AND a.type IN (AUDIO, FULL) AND a.audioProb IS NULL
                    AND a.audioDetails IS NULL))
            """)
    int failSourceIfUnaccepted(@Param("id") UUID id, @Param("source") String source,
            @Param("to") AnalysisStatus to, @Param("msg") String msg,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.status = :to, a.errorMessage = :msg, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int failIfActive(@Param("id") UUID id, @Param("to") AnalysisStatus to, @Param("msg") String msg,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.status = :to, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int cancelIfActive(@Param("id") UUID id, @Param("to") AnalysisStatus to,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    // Conditional hard delete: a concurrent duplicate waits for the winner's commit, then PostgreSQL
    // rechecks the predicate and reports 0 rows instead of a failed entity flush.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Analysis a WHERE a.id = :id AND a.userId = :userId AND a.status IN :terminal")
    int deleteTerminal(@Param("id") UUID id, @Param("userId") String userId,
            @Param("terminal") Collection<AnalysisStatus> terminal);

    // Each unaccepted source owns its heartbeat; sibling progress cannot extend its deadline.
    @Modifying
    @Query("""
            UPDATE Analysis a SET a.status = :to, a.updatedAt = :now,
                a.videoStartedAt = CASE WHEN :source = 'video' THEN COALESCE(a.videoStartedAt, :now) ELSE a.videoStartedAt END,
                a.videoProgressAt = CASE WHEN :source = 'video' THEN :now ELSE a.videoProgressAt END,
                a.audioStartedAt = CASE WHEN :source = 'audio' THEN COALESCE(a.audioStartedAt, :now) ELSE a.audioStartedAt END,
                a.audioProgressAt = CASE WHEN :source = 'audio' THEN :now ELSE a.audioProgressAt END
            WHERE a.id = :id AND a.status IN :active AND (
                (:source = 'video' AND a.type IN (VIDEO, FULL) AND a.videoProb IS NULL) OR
                (:source = 'audio' AND a.type IN (AUDIO, FULL) AND a.audioProb IS NULL AND a.audioDetails IS NULL))
            """)
    int recordProgress(@Param("id") UUID id, @Param("to") AnalysisStatus to,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now,
            @Param("source") String source);

    // Single-row aggregate for the homepage dashboard, scoped to one user (IDOR-free by
    // construction, like findByUserId). SUMs are COALESCEd: over zero rows they yield NULL,
    // which can't unbox into the record's long fields. AVG skips NULL confidences, so it
    // averages COMPLETED analyses only.
    @Query("""
            SELECT new com.deepfake.orchestrator.dto.response.UserStats(
                COUNT(a),
                COALESCE(SUM(CASE WHEN a.status = COMPLETED THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.status = FAILED THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.status = CANCELLED THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.status IN (PENDING, PROCESSING) THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.type = VIDEO THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.type = AUDIO THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.type = FULL THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.verdict = 'FAKE' THEN 1 ELSE 0 END), 0L),
                COALESCE(SUM(CASE WHEN a.verdict = 'REAL' THEN 1 ELSE 0 END), 0L),
                AVG(a.confidence),
                COALESCE(SUM(CASE WHEN a.createdAt >= :since THEN 1 ELSE 0 END), 0L),
                MAX(a.createdAt))
            FROM Analysis a WHERE a.userId = :userId
            """)
    UserStats userStats(@Param("userId") String userId, @Param("since") Instant since);

    @Query(value = "SELECT id FROM analysis WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockForRecovery(@Param("id") UUID id);

    String EXPIRED = """
        status IN ('PENDING', 'PROCESSING') AND (
            created_at < :ageCutoff
            OR (EXISTS (SELECT 1 FROM analysis_task_outbox o WHERE o.analysis_id = analysis.id AND o.sent_at IS NULL)
                AND created_at < :dispatchCutoff)
            OR (NOT EXISTS (SELECT 1 FROM analysis_task_outbox o WHERE o.analysis_id = analysis.id AND o.sent_at IS NULL)
                AND (
                    (type IN ('VIDEO', 'FULL') AND video_prob IS NULL AND (
                        (video_started_at IS NULL AND COALESCE(
                            (SELECT o.sent_at FROM analysis_task_outbox o WHERE o.analysis_id = analysis.id AND o.source = 'video'),
                            updated_at) < :queueCutoff)
                        OR (video_started_at IS NOT NULL AND video_progress_at < :cutoff)))
                    OR (type IN ('AUDIO', 'FULL') AND audio_prob IS NULL AND audio_details IS NULL AND (
                        (audio_started_at IS NULL AND COALESCE(
                            (SELECT o.sent_at FROM analysis_task_outbox o WHERE o.analysis_id = analysis.id AND o.source = 'audio'),
                            updated_at) < :queueCutoff)
                        OR (audio_started_at IS NOT NULL AND audio_progress_at < :cutoff))))))
        """;

    @Query(value = "SELECT id FROM analysis WHERE " + EXPIRED, nativeQuery = true)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, @Param("dispatchCutoff") Instant dispatchCutoff,
            @Param("queueCutoff") Instant queueCutoff, @Param("ageCutoff") Instant ageCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE analysis SET status = 'FAILED', error_message = 'dispatch, queue, progress or absolute deadline exceeded, auto-failed by recovery', updated_at = :now WHERE id = :id AND " + EXPIRED,
            nativeQuery = true)
    int failIfExpired(@Param("id") UUID id, @Param("cutoff") Instant cutoff,
            @Param("dispatchCutoff") Instant dispatchCutoff, @Param("queueCutoff") Instant queueCutoff,
            @Param("ageCutoff") Instant ageCutoff, @Param("now") Instant now);

    // Native + literal statuses so the planner can use the partial index idx_analysis_active
    // (WHERE status IN ('PENDING','PROCESSING')); a bound IN list wouldn't match the index predicate.
    @Query(value = """
            SELECT id FROM analysis
            WHERE status IN ('PENDING', 'PROCESSING') AND updated_at < :cutoff
            """, nativeQuery = true)
    List<UUID> findStuckIds(@Param("cutoff") Instant cutoff);
}
