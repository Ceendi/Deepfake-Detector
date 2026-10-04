package com.deepfake.orchestrator.dto.request;

import com.deepfake.orchestrator.entity.AnalysisType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAnalysisRequest(
        @NotBlank @Size(max = 255) String fileId,
        // Legacy compatibility field; ignored when resolving the analysis input.
        @Size(max = 500) String fileKey,
        @NotNull AnalysisType type,
        AnalysisMode mode) {

    // mode is optional — absent means ACCURATE (mirrors the audio detector's own fallback)
    public CreateAnalysisRequest {
        if (mode == null) {
            mode = AnalysisMode.ACCURATE;
        }
    }
}
