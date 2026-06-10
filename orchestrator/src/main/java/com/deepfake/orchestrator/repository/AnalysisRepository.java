package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
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
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    Page<AnalysisSummary> findByUserId(String userId, Pageable pageable);

    // Atomic aggregation + terminal transitions. clearAutomatically so the caller
    // can re-read fresh; each returns rows touched (0 => already terminal/unknown).

    // Disjoint single-column writes: concurrent video+audio both survive under the Postgres row-lock.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.videoProb = :prob, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int writeVideoProb(@Param("id") UUID id, @Param("prob") BigDecimal prob,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.audioProb = :prob, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int writeAudioProb(@Param("id") UUID id, @Param("prob") BigDecimal prob,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    // CAS the status: 1 row => this caller won the transition, so it runs the side effects exactly once.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.status = :to, a.verdict = :verdict, a.confidence = :confidence, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int complete(@Param("id") UUID id, @Param("to") AnalysisStatus to, @Param("verdict") String verdict,
            @Param("confidence") BigDecimal confidence, @Param("active") Collection<AnalysisStatus> active,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.status = :to, a.errorMessage = :msg, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int failIfActive(@Param("id") UUID id, @Param("to") AnalysisStatus to, @Param("msg") String msg,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Analysis a SET a.status = :to, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int cancelIfActive(@Param("id") UUID id, @Param("to") AnalysisStatus to,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    // Flip PENDING->PROCESSING on the first progress and bump updated_at on every one (heartbeat), in
    // a single atomic UPDATE. Guards on the active set so a late progress on a finished/cancelled
    // analysis matches nothing. PK lookup, so the IN guard doesn't affect index choice. Returns the
    // number of rows touched: >0 means the analysis was active.
    @Modifying
    @Query("UPDATE Analysis a SET a.status = :to, a.updatedAt = :now WHERE a.id = :id AND a.status IN :active")
    int recordProgress(@Param("id") UUID id, @Param("to") AnalysisStatus to,
            @Param("active") Collection<AnalysisStatus> active, @Param("now") Instant now);

    // Native + literal statuses so the planner can use the partial index idx_analysis_active
    // (WHERE status IN ('PENDING','PROCESSING')); a bound IN list wouldn't match the index predicate.
    @Query(value = """
            SELECT id FROM analysis
            WHERE status IN ('PENDING', 'PROCESSING') AND updated_at < :cutoff
            """, nativeQuery = true)
    List<UUID> findStuckIds(@Param("cutoff") Instant cutoff);
}
