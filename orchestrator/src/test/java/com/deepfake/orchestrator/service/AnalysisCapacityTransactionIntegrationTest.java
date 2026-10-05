package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.exception.TooManyAnalysesException;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;

/** Capacity assertions cross real commits and rollbacks, without an enclosing test transaction. */
@DataJpaTest(properties = "backpressure.max-inflight=20")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, BackpressureGuard.class, AnalysisCapacityTransactionIntegrationTest.RedisConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class AnalysisCapacityTransactionIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    @Container
    static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:8.8.0-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AnalysisService service;
    @Autowired BackpressureGuard guard;
    @Autowired AnalysisRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StringRedisTemplate redis;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean AnalysisCache cache;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean AnalysisMetrics metrics;
    private TransactionTemplate transaction;

    @BeforeEach
    void resetState() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.execute("DROP TRIGGER IF EXISTS reject_capacity_test_commit ON analysis");
        repository.deleteAll();
        reset(rabbit);
        redis.opsForValue().set("analyses:inflight", "0");
    }

    @Test
    void brokerUnavailableDoesNotPreventAtomicAdmission() {
        UUID id = create();
        assertThat(active()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task_outbox WHERE analysis_id = ?", Long.class, id))
                .isEqualTo(1);
        verifyNoInteractions(rabbit);
    }

    @Test
    void twentyDatabaseCommitFailuresDoNotBlockTheNextCreate() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION reject_capacity_test_commit() RETURNS trigger AS $$
                BEGIN
                    IF NEW.file_key = 'fail-at-commit' THEN
                        RAISE EXCEPTION 'capacity test deferred commit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER reject_capacity_test_commit AFTER INSERT ON analysis
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_capacity_test_commit()
                """);
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> service.createResolved(request("fail-at-commit"), "alice"))
                    .hasStackTraceContaining("capacity test deferred commit failure");
            assertThat(active()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task_outbox", Long.class)).isZero();
            verifyNoInteractions(rabbit);
        }
        assertThat(create()).isNotNull();
        assertThat(active()).isEqualTo(1);
    }

    @Test
    void invalidIdentifiersAreRejectedBeforePublication() {
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> service.createResolved(request("x".repeat(501)), "alice"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.createResolved(
                new CreateAnalysisRequest("x".repeat(256), "key", AnalysisType.VIDEO, null), "alice"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createResolved(request("key"), "x".repeat(256)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(rabbit);
        assertThat(active()).isZero();
        assertThat(create()).isNotNull();
    }

    @Test
    void terminalRollbackKeepsCapacityOccupiedAndCommitFreesExactlyOnePlace() {
        UUID id = seed(20);
        transaction.executeWithoutResult(status -> {
            service.failFromDlq(id, "test rollback");
            status.setRollbackOnly();
        });
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
        service.failFromDlq(id, "test commit");
        service.failFromDlq(id, "duplicate");
        assertThat(active()).isEqualTo(19);
        create();
        assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
    }

    @Test
    void existingRowsRemainCountedAcrossGuardRecreationDespiteOldRedisDrift() {
        UUID id = seed(20);
        redis.opsForValue().set("analyses:inflight", "9999");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                new BackpressureGuard(jdbc, repository, 20, 5).acquire()))
                .isInstanceOf(TooManyAnalysesException.class);
        service.failFromDlq(id, "free one");
        create();
        assertThat(active()).isEqualTo(20);
        assertThat(redis.opsForValue().get("analyses:inflight")).isEqualTo("9999");
    }

    @Test
    void concurrentAdmissionsDoNotExceedCapacity() throws Exception {
        seed(19);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var attempts = java.util.stream.IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                await(start);
                try {
                    create();
                    return true;
                } catch (TooManyAnalysesException expected) {
                    return false;
                }
            })).toList();
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) {
                if (attempt.get(20, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(active()).isEqualTo(20);
    }

    @Test
    void concurrentAdmissionTerminalCommitAndGuardRecreationKeepCapacityBounded() throws Exception {
        UUID id = seed(20);
        redis.opsForValue().set("analyses:inflight", "9999");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var terminal = pool.submit(() -> {
                await(start);
                service.failFromDlq(id, "concurrent terminal commit");
            });
            var reconstruction = pool.submit(() -> {
                await(start);
                try {
                    transaction.executeWithoutResult(status ->
                            new BackpressureGuard(jdbc, repository, 20, 5).acquire());
                } catch (TooManyAnalysesException expected) {
                    // A recreated admission gate can observe either side of the terminal commit.
                }
            });
            var attempts = java.util.stream.IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                await(start);
                try {
                    create();
                    return true;
                } catch (TooManyAnalysesException expected) {
                    return false;
                }
            })).toList();
            start.countDown();
            terminal.get(20, TimeUnit.SECONDS);
            reconstruction.get(20, TimeUnit.SECONDS);
            int accepted = 0;
            for (var attempt : attempts) {
                if (attempt.get(20, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isBetween(0, 1);
            assertThat(active()).isEqualTo(19 + accepted);
            if (accepted == 0) {
                create();
            }
        }
        assertThat(active()).isEqualTo(20);
        assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
        assertThat(redis.opsForValue().get("analyses:inflight")).isEqualTo("9999");
    }

    @Test
    void waitingAdmissionSeesPrecedingCommit() throws Exception {
        seed(19);
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> transaction.executeWithoutResult(status -> {
                create();
                admitted.countDown();
                await(finish);
            }));
            assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Long.class) == 0) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(10);
            }
            finish.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
        }
        assertThat(active()).isEqualTo(20);
    }

    @Test
    void uncommittedTerminalUpdateCannotAdmitAnotherAnalysis() throws Exception {
        UUID id = seed(20);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var terminal = pool.submit(() -> transaction.executeWithoutResult(status -> {
                service.failFromDlq(id, "not committed yet");
                updated.countDown();
                await(finish);
            }));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
            finish.countDown();
            terminal.get(20, TimeUnit.SECONDS);
        } finally {
            finish.countDown();
        }
        create();
        assertThat(active()).isEqualTo(20);
    }

    @Test
    void repeatedCreatesInOneTransactionCountTheirOwnPendingInserts() {
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < 20; i++) {
                create();
            }
        });
        assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
    }

    @Test
    void admissionRequiresATransactionAndReadCommittedIsolation() {
        assertThatThrownBy(guard::acquire).isInstanceOf(IllegalTransactionStateException.class);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> guard.acquire()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
    }

    @Test
    void redisOutageAndDuplicateTerminalResultsCannotReleaseExtraCapacity() {
        UUID id = seed(20);
        // Pause only the disposable Redis container. Capacity must not touch Redis at all.
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            service.failFromDlq(id, "Redis down");
            service.failFromDlq(id, "duplicate with Redis down");
            create();
            assertThatThrownBy(this::create).isInstanceOf(TooManyAnalysesException.class);
            assertThat(active()).isEqualTo(20);
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    private UUID seed(int count) {
        return transaction.execute(status -> {
            UUID id = null;
            for (int i = 0; i < count; i++) {
                id = repository.save(Analysis.builder().userId("alice")
                        .fileId("file").fileKey("key").type(AnalysisType.VIDEO).build()).getId();
            }
            return id;
        });
    }

    private UUID create() {
        return service.createResolved(request("key"), "alice").id();
    }

    private static CreateAnalysisRequest request(String key) {
        return new CreateAnalysisRequest("file", key, AnalysisType.VIDEO, null);
    }
    private long active() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM analysis WHERE status IN ('PENDING', 'PROCESSING')", Long.class);
    }
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
    @TestConfiguration
    static class RedisConfig {
        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(redisContainer.getHost(), redisContainer.getMappedPort(6379));
        }
        @Bean
        StringRedisTemplate redisTemplate(LettuceConnectionFactory connectionFactory) {
            return new StringRedisTemplate(connectionFactory);
        }
    }
}
