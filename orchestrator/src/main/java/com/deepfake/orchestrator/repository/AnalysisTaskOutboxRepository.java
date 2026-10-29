package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.entity.AnalysisTaskOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface AnalysisTaskOutboxRepository extends JpaRepository<AnalysisTaskOutbox, UUID> {
    @Modifying
    @Query("DELETE FROM AnalysisTaskOutbox o WHERE o.analysisId = :id AND o.sentAt IS NULL")
    int deleteUnsent(@Param("id") UUID id);
}
