package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.entity.Analysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    Page<AnalysisSummary> findByUserId(String userId, Pageable pageable);
}
