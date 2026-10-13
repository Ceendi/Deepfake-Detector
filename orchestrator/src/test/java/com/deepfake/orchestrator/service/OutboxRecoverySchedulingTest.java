package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.deepfake.orchestrator.config.ArtifactCleanupSchedulingConfig;
import com.deepfake.orchestrator.repository.AnalysisRepository;

class OutboxRecoverySchedulingTest {
    @Test
    void blockedRecoveryDoesNotDelayDispatchDue() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(ArtifactCleanupSchedulingConfig.class, Fixtures.class)
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")))
                            context.getEnvironment().getPropertySources().addLast(source);
                    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                })
                .withPropertyValues("reliability.stuck-job.initial-delay=0",
                        "reliability.stuck-job.scan-interval-ms=10000", "reliability.outbox.poll-interval-ms=10")
                .run(context -> {
                    var gates = context.getBean(Gates.class);
                    try {
                        assertThat(gates.recoveryEntered.await(2, TimeUnit.SECONDS)).isTrue();
                        assertThat(gates.dispatched.await(2, TimeUnit.SECONDS))
                                .as("dispatchDue runs while the recovery repository scan is blocked").isTrue();
                        assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class).getPoolSize()).isEqualTo(2);
                    } finally { gates.release.countDown(); }
                });
    }

    static class Gates {
        final CountDownLatch recoveryEntered = new CountDownLatch(1), release = new CountDownLatch(1), dispatched = new CountDownLatch(1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Fixtures {
        @Bean Gates gates() { return new Gates(); }
        @Bean StuckJobRecoveryService recovery(Gates gates) {
            var repository = mock(AnalysisRepository.class);
            when(repository.findExpiredIds(any(), any(), any(), any())).thenAnswer(call -> {
                gates.recoveryEntered.countDown();
                gates.release.await(5, TimeUnit.SECONDS);
                return List.of();
            });
            return new StuckJobRecoveryService(repository, mock(AnalysisService.class), 600, 120, 1800, 7200);
        }
        @Bean TaskOutboxPublisher publisher(Gates gates) {
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
            var jdbc = mock(JdbcTemplate.class, call -> {
                if (call.getMethod().getName().equals("query") && gates.recoveryEntered.getCount() == 0)
                    gates.dispatched.countDown();
                return RETURNS_DEFAULTS.answer(call);
            });
            return new TaskOutboxPublisher(jdbc, mock(RabbitTemplate.class), manager, 5000, 5, 120, 20);
        }
    }
}
