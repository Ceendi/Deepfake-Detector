package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.entity.AnalysisTaskOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AnalysisTaskOutboxRepository extends JpaRepository<AnalysisTaskOutbox, UUID> {}
