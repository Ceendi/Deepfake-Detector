package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.entity.Analysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    // TODO(week 3+): paginate; full per-user list for now (newest first).
    List<Analysis> findAllByUserIdOrderByCreatedAtDesc(String userId);
}
