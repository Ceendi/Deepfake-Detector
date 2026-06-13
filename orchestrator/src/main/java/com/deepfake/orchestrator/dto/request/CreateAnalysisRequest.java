package com.deepfake.orchestrator.dto.request;

import com.deepfake.orchestrator.entity.AnalysisType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAnalysisRequest(
        @NotBlank String fileId,
        @NotBlank String fileKey,
        @NotNull AnalysisType type,
        AnalysisMode mode) {

    // mode is optional — absent means ACCURATE (mirrors the audio detector's own fallback)
    public CreateAnalysisRequest {
        if (mode == null) {
            mode = AnalysisMode.ACCURATE;
        }
    }
}
