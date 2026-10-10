package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.ObjectStorageConfig;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

/** Real migrated PostgreSQL, Spring deletion proxy and production S3 client with HTTP faults. */
@DataJpaTest(showSql = false, properties = {"spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({AnalysisService.class, ObjectStorageConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ArtifactCleanupIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine");
    static final Set<String> objects = ConcurrentHashMap.newKeySet(), failedKeys = ConcurrentHashMap.newKeySet();
    static final AtomicInteger requests = new AtomicInteger();
    static volatile boolean unavailable;
    static volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
    static final java.util.concurrent.ExecutorService handlers = Executors.newFixedThreadPool(4);
    static final HttpServer server = server();
    @Autowired AnalysisService analyses;
    @Autowired AnalysisRepository repository;
    @Autowired ArtifactCleanupStore store;
    @Autowired S3Client s3;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired HikariDataSource pool;
    @MockitoBean BackpressureGuard backpressure;
    @MockitoBean IdempotencyGuard idempotency;
    @MockitoBean AnalysisCache cache;
    @MockitoBean AnalysisStreamRegistry streams;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AnalysisMetrics metrics;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("storage.endpoint", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("storage.access-key", () -> "test"); registry.add("storage.secret-key", () -> "test");
    }
    @BeforeEach void reset() {
        unavailable = false; requests.set(0); objects.clear(); failedKeys.clear();
        entered = new CountDownLatch(0); release = new CountDownLatch(0);
        jdbc.update("DELETE FROM artifact_cleanup"); jdbc.update("DELETE FROM analysis");
        jdbc.update("UPDATE artifact_cleanup_scan SET continuation_token = NULL, lease_token = NULL, lease_until = NULL");
    }
    @AfterAll static void close() { release.countDown(); server.stop(0); handlers.shutdownNow(); }

    @Test void deletionCommitsDuringOutageAndRestartedWorkerEventuallyRemovesTheObject() {
        UUID id = seed("COMPLETED", List.of("legacy/audio/cam.png"));
        objects.add("legacy/audio/cam.png"); unavailable = true;
        analyses.delete(id, "alice");
        assertThat(repository.findById(id)).isEmpty(); assertThat(pending()).isEqualTo(1);
        long start = System.nanoTime(); worker(store).clean();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(7));
        assertThat(objects).contains("legacy/audio/cam.png"); assertThat(pending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM artifact_cleanup", Integer.class)).isEqualTo(1);
        int previousRequests = requests.get(); worker(store).clean();
        assertThat(requests.get()).as("backoff avoids repeated storage waits").isEqualTo(previousRequests);
        // A new store/worker has no in-memory state; durable work and retry time survive restart.
        unavailable = false; due(); worker(restartedStore()).clean();
        assertThat(objects).isEmpty(); assertThat(pending()).isZero();
    }

    @Test void parentDeletionAndCleanupWorkRollbackTogether() {
        UUID id = seed("COMPLETED", List.of("legacy/audio/cam.png"));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            analyses.delete(id, "alice"); assertThat(pending()).isEqualTo(1); tx.setRollbackOnly();
        });
        assertThat(repository.findById(id)).isPresent(); assertThat(pending()).isZero();
        assertThat(requests.get()).isZero();
    }

    @Test void lastSharedReferenceDeletionRetainsCleanupWorkUntilItsTransactionCommits() throws Exception {
        String shared = "legacy/audio/shared.png";
        UUID first = seed("COMPLETED", List.of(shared));
        UUID last = seed("COMPLETED", List.of(shared));
        objects.add(shared);
        analyses.delete(first, "alice");
        assertThat(pending()).isEqualTo(1);
        jdbc.update("UPDATE artifact_cleanup SET attempts = 3 WHERE object_key = ?", shared);
        var enqueued = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var deletion = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                analyses.delete(last, "alice"); // Duplicate enqueue has run; this outer transaction is uncommitted.
                enqueued.countDown();
                try {
                    if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("commit latch timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(enqueued.await(5, TimeUnit.SECONDS)).isTrue();
                long start = System.nanoTime();
                // The row is locked by duplicate enqueue, so SKIP LOCKED must leave it intact. With
                // DO NOTHING a claim instead sees the old reference and discards this legacy work.
                assertThat(store.claim()).isNull();
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
                assertThat(pending()).isEqualTo(1);
                assertThat(requests.get()).isZero();
            } finally { commit.countDown(); }
            deletion.get(5, TimeUnit.SECONDS);
        }
        assertThat(repository.findById(last)).isEmpty();
        assertThat(pending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempts FROM artifact_cleanup", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT analysis_id FROM artifact_cleanup", UUID.class)).isEqualTo(first);
        worker(restartedStore()).clean();
        assertThat(objects).isEmpty(); assertThat(pending()).isZero();
    }

    @Test void duplicateEnqueuePreservesAnExistingLeaseAndRetryMetadata() {
        UUID first = UUID.randomUUID(); String shared = "legacy/audio/shared.png";
        enqueue(first, List.of(shared));
        jdbc.update("UPDATE artifact_cleanup SET attempts = 3 WHERE object_key = ?", shared);
        var work = store.claim();
        var before = jdbc.queryForMap("SELECT * FROM artifact_cleanup");
        enqueue(UUID.randomUUID(), List.of(shared, shared));
        assertThat(jdbc.queryForMap("SELECT * FROM artifact_cleanup")).isEqualTo(before);
        assertThat(restartedStore().claim()).isNull();
        store.complete(work); assertThat(pending()).isZero();
    }

    @Test void partialSuccessDuplicateWorkAndMissingObjectsAreIdempotent() {
        UUID id = UUID.randomUUID(); String failed = "a/audio/cam.png", good = "b/video/cam.png";
        enqueue(id, List.of(failed, good, good, "missing/video/cam.png"));
        enqueue(id, List.of(failed, good)); assertThat(pending()).isEqualTo(3);
        objects.addAll(List.of(failed, good)); failedKeys.add(failed);
        worker(store).clean();
        assertThat(objects).containsExactly(failed); assertThat(pending()).isEqualTo(1);
        failedKeys.clear(); due(); worker(restartedStore()).clean();
        assertThat(objects).isEmpty(); assertThat(pending()).isZero();
    }

    @Test void abandonedLeaseIsRetriedAndStaleCompletionCannotDeleteNewWork() {
        enqueue(UUID.randomUUID(), List.of("legacy/audio/cam.png"));
        var abandoned = store.claim(); assertThat(abandoned.token()).isNotNull();
        assertThat(restartedStore().claim()).isNull();
        jdbc.update("UPDATE artifact_cleanup SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'");
        var replacement = restartedStore().claim();
        assertThat(replacement.token()).isNotEqualTo(abandoned.token());
        store.complete(abandoned); assertThat(pending()).isEqualTo(1);
        store.complete(replacement); assertThat(pending()).isZero();
    }

    @Test void activeAnalysesAndReferencesAcrossAnyAnalysisAreProtectedAtDeletionTime() {
        UUID active = seed("PROCESSING", List.of());
        String activeKey = active + "/audio/gradcam_" + "a".repeat(32) + ".png";
        String referenced = UUID.randomUUID() + "/video/gradcam_frame_00_" + "b".repeat(32) + ".png";
        UUID other = seed("COMPLETED", List.of(referenced));
        enqueue(UUID.randomUUID(), List.of(activeKey)); enqueue(UUID.randomUUID(), List.of(referenced));
        objects.addAll(List.of(activeKey, referenced)); worker(store).clean();
        assertThat(requests.get()).isZero(); assertThat(objects).containsExactlyInAnyOrder(activeKey, referenced);
        assertThat(pending()).isZero(); assertThat(repository.findById(other)).isPresent();
    }

    @Test void scanCursorAndCandidatesCommitAtomicallyAndSurviveRestart() {
        var scan = store.claimScan(); assertThat(scan.continuationToken()).isNull();
        String orphan = UUID.randomUUID() + "/audio/gradcam_" + "a".repeat(32) + ".png";
        store.finishScan(scan, List.of(new ArtifactCleanupStore.Candidate(orphan, UUID.fromString(orphan.substring(0,36)))), "page2");
        assertThat(pending()).isEqualTo(1);
        var next = restartedStore().claimScan(); assertThat(next.continuationToken()).isEqualTo("page2");
        store.releaseScan(next);
        var again = restartedStore().claimScan(); assertThat(again.continuationToken()).isEqualTo("page2");
        store.finishScan(again, List.of(), null);
        assertThat(restartedStore().claimScan().continuationToken()).isNull();
    }

    @Test void failedScanDatabaseBatchRollsBackBothDiscoveredWorkAndCursorAdvance() {
        var scan = store.claimScan();
        var good = new ArtifactCleanupStore.Candidate("legacy/audio/cam.png", UUID.randomUUID());
        var invalid = new ArtifactCleanupStore.Candidate("zz-invalid/audio/cam.png", null);
        assertThatThrownBy(() -> store.finishScan(scan, List.of(good, invalid), "page2"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(pending()).isZero();
        assertThat(jdbc.queryForObject("SELECT continuation_token FROM artifact_cleanup_scan", String.class)).isNull();
        store.releaseScan(scan);
        assertThat(restartedStore().claimScan().continuationToken()).isNull();
    }

    @Test void scanFiltersActiveAndReferencedCandidatesInTheDatabase() {
        UUID active = seed("PENDING", List.of());
        String protectedKey = active + "/audio/gradcam_" + "a".repeat(32) + ".png";
        String referenced = UUID.randomUUID() + "/audio/gradcam_" + "b".repeat(32) + ".png";
        seed("COMPLETED", List.of(referenced));
        UUID garbageId = UUID.randomUUID(); String garbage = garbageId + "/audio/gradcam_" + "c".repeat(32) + ".png";
        store.finishScan(store.claimScan(), List.of(new ArtifactCleanupStore.Candidate(protectedKey, active),
                new ArtifactCleanupStore.Candidate(referenced, UUID.fromString(referenced.substring(0,36))),
                new ArtifactCleanupStore.Candidate(garbage, garbageId)), "page2");
        assertThat(jdbc.queryForList("SELECT object_key FROM artifact_cleanup", String.class)).containsExactly(garbage);
    }

    @Test void concurrentBlockedStorageLeavesBothDatabaseConnectionsAvailable() throws Exception {
        enqueue(UUID.randomUUID(), List.of("legacy/audio/cam.png"));
        objects.add("legacy/audio/cam.png"); entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var cleanup = executor.submit(() -> worker(store).clean());
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                long start = System.nanoTime(); assertThat(repository.count()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(500));
            } finally { release.countDown(); }
            cleanup.get(7, TimeUnit.SECONDS);
        }
        assertThat(pending()).isZero();
    }

    private ArtifactCleanupStore restartedStore() { return new ArtifactCleanupStore(jdbc, manager, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(5)); }
    private ArtifactCleanupWorker worker(ArtifactCleanupStore state) { return new ArtifactCleanupWorker(state, s3, "analysis-artifacts", 10, 100, Duration.ofHours(24)); }
    private int pending() { return jdbc.queryForObject("SELECT COUNT(*) FROM artifact_cleanup", Integer.class); }
    private void due() { jdbc.update("UPDATE artifact_cleanup SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'"); }
    private void enqueue(UUID id, List<String> keys) { new TransactionTemplate(manager).executeWithoutResult(tx -> store.enqueueDeletion(id, keys)); }
    private UUID seed(String status, List<String> keys) {
        UUID id = repository.saveAndFlush(Analysis.builder().userId("alice").fileId("f").fileKey("k").type(AnalysisType.AUDIO)
                .audioDetails(Map.of("gradcamKeys", keys)).build()).getId();
        jdbc.update("UPDATE analysis SET status = ? WHERE id = ?", status, id); return id;
    }
    private static HttpServer server() {
        try {
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); http.setExecutor(handlers);
            http.createContext("/", exchange -> {
                requests.incrementAndGet(); String key = exchange.getRequestURI().getPath().replaceFirst("^/analysis-artifacts/", "");
                entered.countDown();
                try { release.await(7, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (unavailable || failedKeys.contains(key)) {
                    byte[] error = "<Error><Code>ServiceUnavailable</Code><Message>isolated outage</Message></Error>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(503, error.length); exchange.getResponseBody().write(error);
                } else { objects.remove(key); exchange.sendResponseHeaders(204, -1); }
                exchange.close();
            }); http.start(); return http;
        } catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
