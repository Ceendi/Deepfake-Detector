package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.deepfake.orchestrator.config.ArtifactCleanupSchedulingConfig;

class ArtifactCleanupSchedulingTest {
    @Test void blockedCleanupLeavesTheOrdinarySchedulerAvailable() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(ArtifactCleanupSchedulingConfig.class, Fixtures.class).run(context -> {
                var tasks = context.getBean(Tasks.class);
                try {
                    assertThat(tasks.entered.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(tasks.ordinary.await(2, TimeUnit.SECONDS)).as("ordinary scheduling continues during blocked storage").isTrue();
                    assertThat(context.getBean("taskScheduler")).isNotSameAs(context.getBean("artifactCleanupScheduler"));
                    assertThat(context.getBean("artifactCleanupScheduler", ThreadPoolTaskScheduler.class).getPoolSize()).isEqualTo(1);
                } finally { tasks.release.countDown(); }
            });
    }
    @Configuration(proxyBeanMethods = false) @EnableScheduling
    static class Fixtures { @Bean Tasks tasks() { return new Tasks(); } }
    static class Tasks {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), ordinary = new CountDownLatch(1);
        @Scheduled(scheduler = "artifactCleanupScheduler", fixedDelay = 10000)
        public void blocked() throws InterruptedException { entered.countDown(); release.await(5, TimeUnit.SECONDS); }
        @Scheduled(fixedDelay = 10)
        public void ordinary() { if (entered.getCount() == 0) ordinary.countDown(); }
    }
}
