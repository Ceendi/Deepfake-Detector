package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.entity.AnalysisTaskOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AnalysisTaskOutboxRepository extends JpaRepository<AnalysisTaskOutbox, UUID> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM AnalysisTaskOutbox o WHERE o.analysisId = :id AND o.sentAt IS NULL")
    int deleteUnsent(@org.springframework.data.repository.query.Param("id") UUID id);
}
