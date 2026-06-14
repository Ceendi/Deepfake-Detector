package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.dto.response.UserStatsResponse;
import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.metrics.AnalysisMetrics;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
public class AnalysisService {

    private static final BigDecimal W_VIDEO   = new BigDecimal("0.6");
    private static final BigDecimal W_AUDIO   = new BigDecimal("0.4");
    private static final BigDecimal THRESHOLD = new BigDecimal("0.5");
    private static final BigDecimal TWO       = new BigDecimal("2");
    private static final Set<AnalysisStatus> ACTIVE =
            EnumSet.of(AnalysisStatus.PENDING, AnalysisStatus.PROCESSING);

    private final AnalysisRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;
    private final AnalysisCache cache;
    private final AnalysisStreamRegistry streams;
    private final BackpressureGuard backpressure;
    private final IdempotencyGuard idempotency;
    private final AnalysisMetrics metrics;
    private final ResultDetailsExtractor detailsExtractor = new ResultDetailsExtractor();

    public AnalysisResponse create(CreateAnalysisRequest req, String userId) {
        backpressure.acquire(); // 429 here -> nothing persisted or published

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
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_VIDEO, payload);
        }
        if (req.type() == AnalysisType.AUDIO || req.type() == AnalysisType.FULL) {
            // mode is audio-only: it selects the audio model (fast = spectrogram, accurate = Wav2Vec2).
            // Always sent explicitly (request normalizes null -> accurate) so queue payloads are
            // self-describing; the video task keeps the unchanged contract.
            Span.current().setAttribute("analysis.mode", req.mode().wire());
            Map<String, Object> audioPayload = new HashMap<>(payload);
            audioPayload.put("mode", req.mode().wire());
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_AUDIO, audioPayload);
        }

        return AnalysisResponse.from(analysis);
    }

    @Transactional(readOnly = true)
    public AnalysisResponse get(UUID id, String currentUserId) {
        Optional<AnalysisResponse> cached = cache.getById(id);
        metrics.cache(cached.isPresent()); // Redis-down -> empty -> counted as a miss (cache_requests_total)
        AnalysisResponse a = cached.orElseGet(() -> {
            AnalysisResponse loaded = repository.findById(id).map(AnalysisResponse::from).orElse(null);
            if (loaded != null) {
                cache.putById(loaded);
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

    /**
     * Open an SSE stream for one analysis. The IDOR guard at open time is the whole channel
     * authorization: only the owner can register an emitter, so the push side (commit 4) can target
     * by analysisId without re-resolving the owner. An already-finished analysis gets its terminal
     * result immediately and the stream closes, so the client never hangs.
     */
    @Transactional(readOnly = true)
    public SseEmitter openStream(UUID id, String currentUserId) {
        Analysis a = repository.findById(id)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        SseEmitter emitter = streams.register(id);
        if (a.getStatus().isTerminal()) {
            streams.sendResult(id, AnalysisResultEvent.of(a));
            streams.complete(id);
        }
        return emitter;
    }

    @Transactional(readOnly = true)
    public Page<AnalysisSummary> list(String currentUserId, Pageable pageable) {
        // IDOR is enforced by scoping to currentUserId — the query never returns foreign rows.
        return repository.findByUserId(currentUserId, pageable);
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

        Analysis fresh = onTerminal(id); // evict + release + metric + SSE {status: CANCELLED} after commit
        flagCancelAfterCommit(id);
        return AnalysisResponse.from(fresh);
    }

    /**
     * Permanently delete a finished analysis from the caller's history. IDOR -> 404 (like get()).
     * Only a terminal analysis (COMPLETED/FAILED/CANCELLED) can be deleted; an in-progress one -> 409
     * ("cancel it first"): hard-deleting an active analysis would leak its in-flight backpressure slot
     * (released only on a terminal transition) and race the detector still writing its result.
     *
     * <p>Hard delete, by design: the row simply vanishes from every read path (list, get, stats,
     * stream) with no soft-delete predicate to thread through each query — a stale row could never
     * skew {@code userStats}. Terminal state is stable (no transition leaves it), so the read here
     * and the delete need no CAS. The uploaded file and any Grad-CAM artifacts in object storage are
     * deliberately NOT touched: the orchestrator is read-only on {@code analysis-artifacts} and has
     * no access to {@code deepfake-uploads} (docs/contracts/object-storage.md); their reclamation is
     * the storage cleanup job's concern (orphan-by-{analysisId}-prefix), like file soft-delete.
     */
    public void delete(UUID id, String currentUserId) {
        Analysis a = repository.findById(id)
                .filter(found -> found.getUserId().equals(currentUserId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!a.getStatus().isTerminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "cannot delete an in-progress analysis; cancel it first");
        }

        repository.delete(a);
        metrics.deleted(a.getStatus());
        cleanupRedisAfterCommit(id);
    }

    public void handleResult(Map<String, Object> payload) {
        UUID id = UUID.fromString((String) payload.get("analysis_id"));
        String source = (String) payload.get("source"); // "video" | "audio"
        String status = (String) payload.get("status"); // "COMPLETED" | "FAILED"

        // Tie the AMQP consumer span to the analysis (HTTP -> publish -> detector -> result chain).
        Span.current().setAttribute("analysis.id", id.toString());

        if (idempotency.alreadyProcessed(id, source)) {
            log.info("Duplicate result for {}/{}, skipping (idempotency)", id, source);
            return;
        }
        markProcessedAfterCommit(id, source); // recorded only if this tx commits — retry-safe

        if ("FAILED".equals(status)) {
            // TODO(sem2): partial-failure fallback — a FAILED source in FULL fails the whole analysis,
            // dropping the healthy source's prob. Needs a per-source failed flag (migration) + aggregation change.
            if (repository.failIfActive(id, AnalysisStatus.FAILED, extractError(payload), ACTIVE, Instant.now()) == 1) {
                onTerminal(id);
            } else {
                log.warn("Late/duplicate FAILED for {} ({}), ignoring", id, source);
            }
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) payload.get("result");
        BigDecimal prob = new BigDecimal(result.get("prob_fake").toString());
        Map<String, Object> details = detailsExtractor.extract(result);

        int rows = "video".equals(source)
                ? repository.writeVideoProb(id, prob, details, ACTIVE, Instant.now())
                : repository.writeAudioProb(id, prob, details, ACTIVE, Instant.now());
        if (rows == 0) {
            log.warn("Result for terminal/unknown analysis {} ({}), ignoring", id, source);
            return;
        }

        // Fresh read (the @Modifying above cleared the PC): sees BOTH probs when the other source committed.
        Analysis a = repository.findById(id).orElseThrow();
        boolean needV = a.getType() == AnalysisType.VIDEO || a.getType() == AnalysisType.FULL;
        boolean needA = a.getType() == AnalysisType.AUDIO || a.getType() == AnalysisType.FULL;
        if ((needV && a.getVideoProb() == null) || (needA && a.getAudioProb() == null)) {
            cache.evictById(id);
            return; // wait for the other source
        }

        BigDecimal finalProb = aggregate(a.getVideoProb(), a.getAudioProb()); // 0.6V + 0.4A
        String verdict = finalProb.compareTo(THRESHOLD) > 0 ? "FAKE" : "REAL";
        BigDecimal confidence = finalProb.subtract(THRESHOLD).abs().multiply(TWO);

        // CAS completion: only the winner runs onTerminal, so a concurrent cancel can't double-release.
        if (repository.complete(id, AnalysisStatus.COMPLETED, verdict, confidence, ACTIVE, Instant.now()) == 1) {
            onTerminal(id);
        }
    }

    // Dead-lettered message: the DLQ consumer asks us to fail the analysis.
    public void failFromDlq(UUID id, String reason) {
        if (transitionToFailed(id, "dead-letter: " + reason)) {
            metrics.dlqFailure();
        }
    }

    // Stuck-job recovery asks us to fail an analysis that hasn't progressed past its threshold.
    public void failStuck(UUID id, long thresholdSeconds) {
        if (transitionToFailed(id, "stuck > " + thresholdSeconds + "s, auto-failed by recovery")) {
            metrics.stuckRecovery();
        }
    }

    // Shared terminal-failure transition. The CAS is the guard: a late result and recovery firing
    // together still release the slot exactly once (only one gets 1 row). Returns whether this call
    // won, so callers count their cause-specific D6 metric only for the winning transition — keeping
    // analyses_dlq_total / analyses_stuck_recovered_total aligned with analyses_total{status=failed}.
    private boolean transitionToFailed(UUID id, String errorMessage) {
        if (repository.failIfActive(id, AnalysisStatus.FAILED, errorMessage, ACTIVE, Instant.now()) == 1) {
            onTerminal(id);
            return true;
        }
        log.info("fail requested for non-active analysis {}, ignoring", id);
        return false;
    }

    // Side effects shared by every terminal transition (status already CAS-written). Re-reads because
    // the @Modifying CAS cleared the PC; that fresh row also carries the status/type/createdAt the
    // metrics need, so every terminal path (result, fail, DLQ, stuck, cancel) is counted here once.
    private Analysis onTerminal(UUID id) {
        cache.evictById(id);
        backpressure.release();
        Analysis a = repository.findById(id).orElseThrow();
        metrics.terminal(a.getStatus(), a.getType());
        if (a.getStatus() == AnalysisStatus.COMPLETED) {
            metrics.duration(a.getType(), Duration.between(a.getCreatedAt(), Instant.now()));
        }
        pushTerminalAfterCommit(id, a);
        return a;
    }

    public void handleProgress(Map<String, Object> payload) {
        try {
            UUID id = UUID.fromString((String) payload.get("analysis_id"));
            Integer progress = (Integer) payload.get("progress");

            // Atomic flip-or-heartbeat; 0 rows means the analysis is already terminal/unknown, so a
            // late progress ping is dropped (no stale PROCESSING push, no resurrecting a cancelled one).
            if (repository.recordProgress(id, AnalysisStatus.PROCESSING, ACTIVE, Instant.now()) == 0) {
                return;
            }
            cache.evictById(id); // status may have flipped PENDING->PROCESSING; keep GET fresh
            snapshotProgress(id, progress);
            streams.sendProgress(id, new AnalysisProgressEvent(
                    id.toString(), (String) payload.get("source"), progress,
                    (String) payload.get("stage"), "PROCESSING"));
        } catch (Exception e) {
            // A progress ping is advisory: losing one must never fail the analysis (the shared
            // retry/recoverer would otherwise dead-letter it as if it were a failed result).
            log.warn("progress ping dropped for {}: {}", payload.get("analysis_id"), e.toString());
        }
    }

    // Redis snapshot for catch-up (GET / reconnect). Fail-open so a Redis outage degrades the
    // snapshot only — the live SSE push (the primary path) must still run, hence the inner catch.
    private void snapshotProgress(UUID id, Integer progress) {
        try {
            redis.opsForValue().set("progress:" + id, progress.toString(), Duration.ofHours(1));
        } catch (DataAccessException e) {
            log.warn("progress snapshot skipped (Redis down): {}", e.getMessage());
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

    // Set after commit so a rolled-back cancel can't leave a flag that aborts a live analysis
    // (same reasoning as markProcessedAfterCommit). Fail-open: Redis down degrades to today's
    // behavior — detectors finish and the late result bounces off the DB terminal-state guard.
    // TTL covers queue wait + processing of the longest accepted file; an expired flag has the
    // same benign degradation.
    private void flagCancelAfterCommit(UUID id) {
        Runnable setFlag = () -> {
            try {
                redis.opsForValue().set("cancel:" + id, "1", Duration.ofHours(2));
            } catch (DataAccessException e) {
                log.warn("cancel flag skipped (Redis down) for {}: {}", id, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { setFlag.run(); }
            });
        } else {
            setFlag.run();
        }
    }

    // Drop the deleted analysis's whole Redis footprint, but only after the delete commits — a
    // rollback must leave the cache warm and the keys intact. Evicting the cache is the correctness-
    // critical part: a cached AnalysisResponse would otherwise let GET serve a row the DB no longer
    // has (cache hit -> IDOR passes -> stale 200 instead of 404). The progress/cancel/dedup keys are
    // residual hygiene (TTL'd, UUID never reused), cleaned best-effort. Fail-open: a Redis outage
    // must never fail a committed delete.
    private void cleanupRedisAfterCommit(UUID id) {
        Runnable cleanup = () -> {
            cache.evictById(id);     // own try/catch (fail-open)
            idempotency.clear(id);   // own try/catch (fail-open)
            try {
                redis.delete(List.of("progress:" + id, "cancel:" + id));
            } catch (DataAccessException e) {
                log.warn("post-delete Redis cleanup skipped (Redis down) for {}: {}", id, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.run(); }
            });
        } else {
            cleanup.run();
        }
    }

    private void pushTerminalAfterCommit(UUID id, Analysis a) {
        AnalysisResultEvent ev = AnalysisResultEvent.of(a);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    streams.sendResult(id, ev);
                    streams.complete(id);
                }
            });
        } else {
            streams.sendResult(id, ev);
            streams.complete(id);
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
