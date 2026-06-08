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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    Page<AnalysisSummary> findByUserId(String userId, Pageable pageable);

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
