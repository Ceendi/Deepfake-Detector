package com.deepfake.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ArtifactCleanupSchedulingConfig {
    // Non-default candidate leaves Boot's ordinary scheduler (outbox/recovery/heartbeat) intact.
    // @Scheduled selects this bean explicitly by its qualifier/name.
    @Bean(defaultCandidate = false)
    public ThreadPoolTaskScheduler artifactCleanupScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("artifact-cleanup-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
