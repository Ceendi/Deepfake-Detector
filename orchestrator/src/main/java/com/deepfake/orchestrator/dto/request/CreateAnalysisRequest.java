package com.deepfake.orchestrator.dto.request;

import com.deepfake.orchestrator.entity.AnalysisType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAnalysisRequest(
        @NotBlank String fileId,
        @NotBlank String fileKey,
        @NotNull AnalysisType type) {}
