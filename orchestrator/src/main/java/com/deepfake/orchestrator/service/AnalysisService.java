package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.redis.OptionalRedisOperations;
import com.deepfake.orchestrator.listener.InvalidResultException;
import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.entity.AnalysisTaskOutbox;
import com.deepfake.orchestrator.repository.AnalysisTaskOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.dto.response.UserStatsResponse;
import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AudioApplicability;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
@Import({OptionalRedisOperations.class, ArtifactCleanupStore.class})
public class AnalysisService {

    private static final BigDecimal W_VIDEO   = new BigDecimal("0.6");
    private static final BigDecimal W_AUDIO   = new BigDecimal("0.4");
    private static final BigDecimal THRESHOLD = new BigDecimal("0.5");
    private static final BigDecimal TWO       = new BigDecimal("2");
    private static final Set<AnalysisStatus> ACTIVE =
            EnumSet.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING);
    private static final Set<AnalysisStatus> TERMINAL =
            EnumSet.of(AnalysisStatus.COMPLETED, AnalysisStatus.FAILED, AnalysisStatus.CANCELLED);

    private final AnalysisRepository repository;
    private final AnalysisTaskOutboxRepository outbox;
    @Value("${reliability.outbox.dispatch-timeout-seconds:120}")
    private long dispatchTimeoutSeconds = 120;
    @Value("${reliability.stuck-job.queue-timeout-seconds:1800}")
    private long queueTimeoutSeconds = 1800;
    @Value("${reliability.stuck-job.max-age-seconds:7200}")
    private long maxAgeSeconds = 7200;
    private final StringRedisTemplate redis;
    private final OptionalRedisOperations optionalRedis;
    private final AnalysisCache cache;
    private final AnalysisStreamRegistry streams;
    private final BackpressureGuard backpressure;
    private final IdempotencyGuard idempotency;
    private final AnalysisMetrics metrics;
    private final ArtifactCleanupStore artifactCleanup;
    private final ResultDetailsExtractor detailsExtractor = new ResultDetailsExtractor();

    /** Transactional writer: only the creation facade supplies a resolved canonical file key. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnalysisResponse createResolved(CreateAnalysisRequest req, String userId) {
        validateCreate(req, userId);
        backpressure.acquire(userId); // 429 here -> nothing persisted or published

        Analysis analysis = Analysis.builder()
                .userId(userId)
                .fileId(req.fileId())
                .fileKey(req.fileKey())
                .type(req.type())
                .build();

        analysis = repository.save(analysis);

        // Business attributes on the current (HTTP) span -> searchable in Tempo. Span-level cardinality
        // is fine here (a span is one event, unlike a metric series). No-op if no span is active.
        Span.current().setAttribute("analysis.id", analysis.getId().toString());
        Span.current().setAttribute("analysis.type", analysis.getType().name());

        // Carry the request's correlation_id onto the task so detectors echo it and the whole
        // chain (HTTP -> AMQP -> detector -> result) shares one id. Fallback for non-HTTP callers.
        String correlationId = MDC.get("correlationId");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        Map<String, Object> payload = Map.of(
                "analysis_id",   analysis.getId().toString(),
                "file_bucket",   "deepfake-uploads",
                "file_key",      req.fileKey(),
                "correlation_id", correlationId,
                "timestamp",     Instant.now().toString()
        );

        if (req.type() == AnalysisType.VIDEO || req.type() == AnalysisType.FULL) {
            enqueue(analysis.getId(), "video", payload);
        }
        if (req.type() == AnalysisType.AUDIO || req.type() == AnalysisType.FULL) {
            // mode is audio-only: it selects the audio model (fast = spectrogram, accurate = Wav2Vec2).
            // Always sent explicitly (request normalizes null -> accurate) so queue payloads are
            // self-describing; the video task keeps the unchanged contract.
            Span.current().setAttribute("analysis.mode", req.mode().wire());
            Map<String, Object> audioPayload = new HashMap<>(payload);
            audioPayload.put("mode", req.mode().wire());
            enqueue(analysis.getId(), "audio", audioPayload);
        }

        return AnalysisResponse.from(analysis);
    }

    private void enqueue(UUID analysisId, String source, Map<String, Object> payload) {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> durablePayload = new HashMap<>(payload);
        durablePayload.put("task_id", taskId.toString());
        Map<String, String> trace = new HashMap<>();
        io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance().inject(
                io.opentelemetry.context.Context.current(), trace, Map::put);
        outbox.save(AnalysisTaskOutbox.builder().id(taskId).analysisId(analysisId)
                .source(source).payload(durablePayload).traceparent(trace.get("traceparent")).build());
    }

    private static void validateCreate(CreateAnalysisRequest req, String userId) {
        if (req == null || req.type() == null
                || invalidIdentifier(req.fileId(), 255) || invalidIdentifier(req.fileKey(), 500)
                || invalidIdentifier(userId, 255)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analysis identifiers or type");
        }
    }

    private static boolean invalidIdentifier(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }

    @Transactional(readOnly = true)
    public AnalysisResponse get(UUID id, String currentUserId) {
        AnalysisCache.FillToken fill = cache.beginFill(id);
        Optional<AnalysisResponse> cached = cache.getById(id);
        metrics.cache(cached.isPresent()); // Redis-down -> empty -> counted as a miss (cache_requests_total)
        AnalysisResponse a = cached.orElseGet(() -> {
            AnalysisResponse loaded = repository.findById(id).map(AnalysisResponse::from).orElse(null);
            if (loaded != null) {
                cache.putById(loaded, fill);
            }
            return loaded;
        });
        // IDOR guard runs after the cache read (404 not 403), so a cache hit on a foreign id still
        // 404s instead of leaking another user's data — see AnalysisServiceCacheIdorTest.
        if (a == null || !a.userId().equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return a;
    }

    /** Register between two independent committed reads; completion is either pushed or replayed. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SseEmitter openStream(UUID id, String currentUserId) {
        repository.streamSnapshot(id, currentUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        SseEmitter emitter = streams.register(id, currentUserId);
        try {
            var fresh = repository.streamSnapshot(id, currentUserId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (fresh.status().isTerminal()) {
                streams.sendResult(id, emitter, fresh.result());
            }
            return emitter;
        } catch (RuntimeException ex) {
            streams.discard(id, emitter);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Page<AnalysisSummary> list(String currentUserId, Pageable pageable) {
        Pageable history = PageRequest.of(
                pageable.getPageNumber(), Math.min(pageable.getPageSize(), 100),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        // JPA accepts an int offset; reject overflow before it becomes a persistence failure.
        if (history.getOffset() > Integer.MAX_VALUE) {
            throw new org.springframework.beans.TypeMismatchException(history.getOffset(), Integer.class);
        }
        // IDOR is enforced by scoping to currentUserId — the query never returns foreign rows.
        return repository.findByUserId(currentUserId, history);
    }

    @Transactional(readOnly = true)
    public UserStatsResponse stats(String currentUserId) {
        // Same IDOR-by-construction scoping as list(). Uncached: the aggregate rides the covering
        // index idx_analysis_user_created, and a stale in-progress count on the homepage would
        // contradict the live SSE updates.
        return UserStatsResponse.from(
                repository.userStats(currentUserId, Instant.now().minus(7, ChronoUnit.DAYS)));
    }

    /**
     * Soft-cancel an in-progress analysis. IDOR -> 404 (like get()). Idempotent on CANCELLED;
     * a finished analysis (COMPLETED/FAILED) -> 409. Frees the in-flight slot, closes the SSE
     * stream as CANCELLED, and flags cancel:{id} in Redis so detectors abort in-flight/queued
     * work early (cooperative cancellation, amqp-messages.md). Best-effort: the DB terminal
     * state stays the correctness authority — without the flag detectors just finish and their
     * late result is ignored.
     */
    public AnalysisResponse cancel(UUID id, String currentUserId) {
        Analysis a = repository.findById(id)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (a.getStatus() == AnalysisStatus.CANCELLED) {
            return AnalysisResponse.from(a); // idempotent — slot already released on first cancel
        }
        if (a.getStatus() == AnalysisStatus.COMPLETED || a.getStatus() == AnalysisStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "analysis already finished");
        }

        // CAS: a detector result may complete the analysis between findById and here. Only the winner
        // releases / publishes / pushes; the loser re-reads and answers truthfully.
        if (repository.cancelIfActive(id, AnalysisStatus.CANCELLED, ACTIVE, Instant.now()) == 0) {
            Analysis fresh = repository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (fresh.getStatus() == AnalysisStatus.CANCELLED) {
                return AnalysisResponse.from(fresh); // a concurrent duplicate cancel won; idempotent
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "analysis already finished");
        }

        Analysis fresh = onTerminal(id); // evict + metric + SSE {status: CANCELLED} after commit
        return AnalysisResponse.from(fresh);
    }

    /**
     * Permanently delete a finished analysis from the caller's history. IDOR -> 404 (like get()).
     * Only a terminal analysis (COMPLETED/FAILED/CANCELLED) can be deleted; an in-progress one -> 409
     * ("cancel it first"): hard-deleting an active analysis would race ongoing detector work
     * and discard its state before a terminal transition.
     *
     * <p>Hard delete, by design: the row simply vanishes from every read path (list, get, stats,
     * stream) with no soft-delete predicate to thread through each query — a stale row could never
     * skew {@code userStats}. Terminal state is stable (no transition leaves it), so the status read
     * here stays valid; the delete itself is conditional, so a concurrent duplicate request gets 404.
     *
     * <p>Artifact deletion work is persisted in this same transaction and survives parent deletion.
     * The worker retries artifact storage outside database transactions. Returns captured keys for internal callers.
     */
    public List<String> delete(UUID id, String currentUserId) {
        Analysis a = repository.findById(id)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        AnalysisStatus status = a.getStatus();
        if (!status.isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot delete an in-progress analysis; cancel it first");
        }

        List<String> artifactKeys = gradcamKeysOf(a); // capture before the row (and its details) go away
        if (repository.deleteTerminal(id, currentUserId, TERMINAL) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND); // a concurrent delete won
        }
        artifactCleanup.enqueueDeletion(id, artifactKeys); // only the winner, same transaction
        afterCommit(() -> metrics.deleted(status));
        cleanupRedisAfterCommit(id);
        if (status != AnalysisStatus.COMPLETED) {
            // Queued or in-flight detector work may outlive the row; keep (refresh) its stop signal.
            flagCancelAfterCommit(id);
        }
        return artifactKeys;
    }

    /** The lifecycle facade serializes this transaction with claims of the same input file. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteWithInput(UUID id, String userId, Runnable releaseInput) {
        Analysis analysis = repository.findById(id).filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String fileId = analysis.getFileId();
        delete(id, userId);
        if (!repository.existsByUserIdAndFileId(userId, fileId)) {
            // A failed release rolls back the record deletion so the caller can safely retry.
            releaseInput.run();
        }
    }

    // The raw analysis-artifacts object keys recorded across this analysis's per-source details
    // (video + audio); empty when no detector produced a heatmap. Same gradcamKeys shape the
    // ArtifactService membership check and AnalysisResponse URL mapping read.
    private List<String> gradcamKeysOf(Analysis a) {
        List<String> keys = new ArrayList<>();
        addGradcamKeys(a.getVideoDetails(), keys);
        addGradcamKeys(a.getAudioDetails(), keys);
        return keys;
    }

    private static void addGradcamKeys(Map<String, Object> details, List<String> out) {
        if (details != null && details.get("gradcamKeys") instanceof Collection<?> keys) {
            keys.forEach(k -> out.add(k.toString()));
        }
    }

    public void handleResult(Map<String, Object> payload) {
        UUID id;
        try {
            id = UUID.fromString((String) payload.get("analysis_id"));
            if (!id.toString().equalsIgnoreCase((String) payload.get("analysis_id"))) {
                throw new InvalidResultException("Invalid analysis_id");
            }
        } catch (RuntimeException invalidId) {
            throw new InvalidResultException("Invalid analysis_id");
        }
        Object sourceValue = payload.get("source");
        if (!(sourceValue instanceof String source)
                || !("video".equals(source) || "audio".equals(source))) {
            log.warn("Ignoring result with invalid source for {}: {}", id, sourceValue);
            return;
        }
        Object status = payload.get("status");
        if (!("COMPLETED".equals(status) || "FAILED".equals(status))) {
            log.warn("Ignoring non-terminal result for {} ({}): {}", id, source, status);
            return;
        }

        BigDecimal prob = null;
        Map<String, Object> result = null;
        if ("COMPLETED".equals(status)) {
            if (!(payload.get("result") instanceof Map<?, ?> values) || values.get("prob_fake") == null) {
                throw new InvalidResultException("Completed result requires prob_fake");
            }
            try {
                prob = new BigDecimal(values.get("prob_fake").toString());
            } catch (NumberFormatException invalidScore) {
                throw new InvalidResultException("Invalid prob_fake");
            }
            if (prob.compareTo(BigDecimal.ZERO) < 0 || prob.compareTo(BigDecimal.ONE) > 0) {
                throw new InvalidResultException("prob_fake outside [0, 1]");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> validated = (Map<String, Object>) values;
            result = validated;
        }

        // Tie the AMQP consumer span to the analysis (HTTP -> publish -> detector -> result chain).
        Span.current().setAttribute("analysis.id", id.toString());

        if (idempotency.alreadyProcessed(id, source)) {
            log.info("Duplicate result for {}/{}, skipping (idempotency)", id, source);
            return;
        }

        if ("FAILED".equals(status)) {
            if ("audio".equals(source) && payload.get("error") instanceof Map<?, ?> error
                    && AudioApplicability.isSupportedReason(error.get("code"))) {
                String reason = (String) error.get("code");
                String message = error.get("message") instanceof String value ? value
                        : AudioApplicability.defaultMessage(reason);
                if (repository.writeAudioNotApplicable(id, AudioApplicability.notApplicable(reason, message),
                        ACTIVE, Instant.now()) == 1) {
                    markProcessedAfterCommit(id, source);
                    finishIfReady(id);
                    return;
                }
            }
            // All other unaccepted expected-source failures retain whole-analysis failure.
            if (repository.failSourceIfUnaccepted(id, source, AnalysisStatus.FAILED,
                    extractError(payload), ACTIVE, Instant.now()) == 1) {
                markProcessedAfterCommit(id, source);
                onTerminal(id);
            } else {
                log.warn("Late/duplicate FAILED for {} ({}), ignoring", id, source);
            }
            return;
        }

        Map<String, Object> details = detailsExtractor.extract(result, id, source);

        int rows = "video".equals(source)
                ? repository.writeVideoProb(id, prob, details, ACTIVE, Instant.now())
                : repository.writeAudioProb(id, prob, details, ACTIVE, Instant.now());
        if (rows == 0) {
            log.warn("Result for accepted/unexpected source or terminal/unknown analysis {} ({}), ignoring", id, source);
            return;
        }

        markProcessedAfterCommit(id, source);
        finishIfReady(id);
    }

    private void finishIfReady(UUID id) {
        // Fresh read (the @Modifying above cleared the PC): sees BOTH probs when the other source committed.
        Analysis a = repository.findById(id).orElseThrow();
        boolean needV = a.getType() == AnalysisType.VIDEO || a.getType() == AnalysisType.FULL;
        boolean needA = a.getType() == AnalysisType.AUDIO || a.getType() == AnalysisType.FULL;
        boolean audioNotApplicable = a.getType() == AnalysisType.FULL
                && AudioApplicability.isNotApplicable(a.getAudioDetails());
        if ((needV && a.getVideoProb() == null)
                || (needA && a.getAudioProb() == null && !audioNotApplicable)) {
            evictCacheAfterCommit(id);
            return; // wait for the other source
        }

        // Only explicit FULL audio inapplicability may complete without audio; aggregate then uses video alone.
        BigDecimal finalProb = aggregate(a.getVideoProb(), a.getAudioProb());
        String verdict = finalProb.compareTo(THRESHOLD) > 0 ? "FAKE" : "REAL";
        BigDecimal confidence = finalProb.subtract(THRESHOLD).abs().multiply(TWO);

        // CAS completion: only the winner runs onTerminal, so a concurrent cancel can't double-release.
        if (repository.complete(id, AnalysisStatus.COMPLETED, verdict, confidence, ACTIVE, Instant.now()) == 1) {
            onTerminal(id);
        }
    }

    // Task and result DLQs identify one source. An already accepted FULL source must not fail
    // the analysis while its other source is still running; the database CAS is authoritative.
    public void failFromDlq(UUID id, String source, String reason) {
        if (!("video".equals(source) || "audio".equals(source))) {
            throw new IllegalArgumentException("Unknown dead-letter source");
        }
        log.warn("Dead-lettered analysis_id={} source={} reason={}", id, source, reason);
        if (repository.failSourceIfUnaccepted(id, source, AnalysisStatus.FAILED,
                "[DEAD_LETTERED] Nie udało się zakończyć analizy.", ACTIVE, Instant.now()) == 1) {
            onTerminal(id);
            afterCommit(metrics::dlqFailure);
        }
    }

    // Stuck-job recovery asks us to fail an analysis that hasn't progressed past its threshold.
    public void failStuck(UUID id, long thresholdSeconds) {
        // Lock first, then use a new statement snapshot: a publisher/progress/cancel may have
        // changed the candidate since the scan. Never finish based only on that earlier scan.
        if (repository.lockForRecovery(id).isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        if (repository.failIfExpired(id, now.minusSeconds(thresholdSeconds),
                now.minusSeconds(dispatchTimeoutSeconds), now.minusSeconds(queueTimeoutSeconds),
                now.minusSeconds(maxAgeSeconds), now) == 1) {
            onTerminal(id);
            afterCommit(metrics::stuckRecovery);
        }
    }

    private void evictCacheAfterCommit(UUID id) {
        Runnable evict = () -> {
            try { cache.evictById(id); }
            catch (RuntimeException failure) { log.warn("Committed cache eviction failed for {}", id, failure); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict.run(); }
            });
        } else {
            evict.run();
        }
    }

    // Side effects shared by every terminal transition (status already CAS-written). Re-reads because
    // the @Modifying CAS cleared the PC; that fresh row also carries the status/type/createdAt the
    // metrics need, so every terminal path (result, fail, DLQ, stuck, cancel) is counted here once.
    // Metrics record only committed transitions; a rolled-back (then retried) CAS counts nothing.
    private Analysis onTerminal(UUID id) {
        outbox.deleteUnsent(id);
        evictCacheAfterCommit(id);
        Analysis a = repository.findById(id).orElseThrow();
        AnalysisStatus status = a.getStatus();
        AnalysisType type = a.getType();
        Instant createdAt = a.getCreatedAt();
        afterCommit(() -> {
            metrics.terminal(status, type);
            if (status == AnalysisStatus.COMPLETED) {
                metrics.duration(type, Duration.between(createdAt, Instant.now()));
            }
        });
        if (a.getStatus() == AnalysisStatus.FAILED || a.getStatus() == AnalysisStatus.CANCELLED) {
            flagCancelAfterCommit(id);
        }
        pushTerminalAfterCommit(id, a);
        return a;
    }

    public void handleProgress(Map<String, Object> payload) {
        UUID id = UUID.fromString((String) payload.get("analysis_id"));
        Integer progress = (Integer) payload.get("progress");
        if (progress == null || progress < 0 || progress > 100) {
            throw new IllegalArgumentException("Progress must be between 0 and 100");
        }
        String source = (String) payload.get("source");
        if (!("video".equals(source) || "audio".equals(source))) return;
        if (repository.recordProgress(id, AnalysisStatus.PROCESSING, ACTIVE, Instant.now(), source) == 0) return;
        AnalysisProgressEvent event = new AnalysisProgressEvent(id.toString(),
                (String) payload.get("source"), progress, (String) payload.get("stage"), "PROCESSING");
        Runnable publish = () -> {
            // Advisory effects cannot change acknowledgement after the database already committed.
            try { cache.evictById(id); }
            catch (RuntimeException failure) { log.warn("Progress cache eviction failed for {}", id, failure); }
            try { streams.sendProgress(id, event); }
            catch (RuntimeException failure) { log.warn("Progress delivery failed for {}", id, failure); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    // Push the terminal result after commit, so a rollback can't leak a state the DB discards. Build
    // the event now while the entity is managed (open-in-view:false closes the session at commit).
    private void markProcessedAfterCommit(UUID id, String source) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { idempotency.markProcessed(id, source); }
            });
        } else {
            idempotency.markProcessed(id, source);
        }
    }

    // A rolled-back failure/cancel must not abort live work. Redis remains an optional compute hint.
    private void flagCancelAfterCommit(UUID id) {
        Runnable setFlag = () -> optionalRedis.run("cancel hint", () ->
                redis.opsForValue().set("cancel:" + id, "1", Duration.ofHours(2)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { setFlag.run(); }
            });
        } else {
            setFlag.run();
        }
    }

    // Drop the deleted analysis's Redis footprint, but only after the delete commits — a rollback
    // must leave the cache warm and the keys intact. Evicting the cache is the correctness-critical
    // part: a cached AnalysisResponse would otherwise let GET serve a row the DB no longer has
    // (cache hit -> IDOR passes -> stale 200 instead of 404). The progress/dedup keys are residual
    // hygiene (TTL'd, UUID never reused), cleaned best-effort. cancel:{id} is deliberately kept: it is
    // the only stop signal for detector work still queued after the row is gone. Fail-open: a Redis
    // outage must never fail a committed delete.
    private void cleanupRedisAfterCommit(UUID id) {
        afterCommit(() -> {
            cache.evictById(id);     // optional Redis, fail-open
            idempotency.clear(id);   // optional Redis, fail-open
            optionalRedis.run("analysis cleanup", () -> redis.delete(List.of("progress:" + id)));
        });
    }

    // Effects of committed state only; outside a transaction (unit tests, direct calls) run inline.
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private void pushTerminalAfterCommit(UUID id, Analysis a) {
        AnalysisResultEvent ev = AnalysisResultEvent.of(a);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    streams.sendResult(id, ev);
                }
            });
        } else {
            streams.sendResult(id, ev);
        }
    }

    private BigDecimal aggregate(BigDecimal videoProb, BigDecimal audioProb) {
        if (videoProb == null) return audioProb;
        if (audioProb == null) return videoProb;
        return videoProb.multiply(W_VIDEO).add(audioProb.multiply(W_AUDIO));
    }

    private String extractError(Map<String, Object> payload) {
        Object err = payload.get("error");
        if (err instanceof Map<?, ?> m) {
            return String.format("[%s] %s", m.get("code"), m.get("message"));
        }
        return err == null ? "unknown" : err.toString();
    }
}
