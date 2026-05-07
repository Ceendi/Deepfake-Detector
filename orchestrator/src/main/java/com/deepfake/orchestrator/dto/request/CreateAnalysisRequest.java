package com.deepfake.orchestrator.dto.request;

import com.deepfake.orchestrator.entity.AnalysisType;

public record CreateAnalysisRequest(String fileId, String fileKey, AnalysisType type) {}
