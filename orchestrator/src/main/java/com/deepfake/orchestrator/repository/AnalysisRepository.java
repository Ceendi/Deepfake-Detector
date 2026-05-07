package com.deepfake.orchestrator.repository;

import com.deepfake.orchestrator.entity.Analysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

}
