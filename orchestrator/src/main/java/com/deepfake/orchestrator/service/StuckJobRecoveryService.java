package com.deepfake.orchestrator.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.deepfake.orchestrator.repository.AnalysisRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Periodically fails analyses that have been active but silent past a threshold — a detector that
 * crashed or never picked up the task (D6). Each job is failed in its own transaction (via
 * AnalysisService) so one failure doesn't roll back the rest, and failing the job releases its
 * in-flight slot, reconciling the backpressure gauge after a mid-flight crash.
 */
@Slf4j
@Service
public class StuckJobRecoveryService {

    private final AnalysisRepository repository;
    private final AnalysisService analysisService;
    private final long thresholdSeconds;

    public StuckJobRecoveryService(AnalysisRepository repository, AnalysisService analysisService,
            @Value("${reliability.stuck-job.threshold-seconds:600}") long thresholdSeconds) {
        this.repository = repository;
        this.analysisService = analysisService;
        this.thresholdSeconds = thresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${reliability.stuck-job.scan-interval-ms:300000}")
    public void reclaimStuck() {
        List<UUID> stuck = repository.findStuckIds(Instant.now().minusSeconds(thresholdSeconds));
        if (stuck.isEmpty()) {
            return;
        }
        log.warn("stuck-job recovery: reclaiming {} analyses idle > {}s", stuck.size(), thresholdSeconds);
        for (UUID id : stuck) {
            try {
                analysisService.failStuck(id, thresholdSeconds);
            } catch (Exception e) {
                log.error("stuck-job recovery failed for {}, continuing", id, e);
            }
        }
    }
}
