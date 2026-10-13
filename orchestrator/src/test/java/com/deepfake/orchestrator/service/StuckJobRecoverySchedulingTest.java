package com.deepfake.orchestrator.service;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.deepfake.orchestrator.repository.AnalysisRepository;

class StuckJobRecoverySchedulingTest {
    @EnableScheduling
    static class Scheduling {}

    @Test
    void firstScanWaitsForConfiguredIdleThreshold() throws Exception {
        var repository = mock(AnalysisRepository.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "reliability.stuck-job.threshold-seconds", "1",
                    "reliability.stuck-job.scan-interval-ms", "10000")));
            context.register(Scheduling.class);
            context.registerBean(StuckJobRecoveryService.class, () ->
                    new StuckJobRecoveryService(repository, mock(AnalysisService.class), 1, 120, 1800, 7200));
            context.refresh();
            Thread.sleep(200);
            verifyNoInteractions(repository);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    verify(repository).findExpiredIds(any(), any(), any(), any()));
        }
    }
}
