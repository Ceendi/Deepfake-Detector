package com.deepfake.orchestrator.entity;

public enum AnalysisStatus {
    PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
