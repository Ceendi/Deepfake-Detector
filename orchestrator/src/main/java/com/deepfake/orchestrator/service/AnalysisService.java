package com.deepfake.orchestrator.service;

import com.deepfake.orchestrator.cache.AnalysisCache;
import com.deepfake.orchestrator.config.RabbitConfig;
import com.deepfake.orchestrator.dto.request.CreateAnalysisRequest;
import com.deepfake.orchestrator.dto.response.AnalysisResponse;
import com.deepfake.orchestrator.dto.response.AnalysisSummary;
import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisStatus;
import com.deepfake.orchestrator.entity.AnalysisType;
import com.deepfake.orchestrator.repository.AnalysisRepository;
import com.deepfake.orchestrator.sse.AnalysisStreamRegistry;
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
import java.util.EnumSet;
import java.util.Map;
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

    public AnalysisResponse create(CreateAnalysisRequest req, String userId) {
        backpressure.acquire(); // 429 here -> nothing persisted or published

        Analysis analysis = Analysis.builder()
                .userId(userId)
                .fileId(req.fileId())
                .fileKey(req.fileKey())
                .type(req.type())
                .build();

        analysis = repository.save(analysis);

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
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_AUDIO, payload);
        }

        return AnalysisResponse.from(analysis);
    }

    @Transactional(readOnly = true)
    public AnalysisResponse get(UUID id, String currentUserId) {
        AnalysisResponse a = cache.getById(id).orElseGet(() -> {
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

    /**
     * Soft-cancel an in-progress analysis. IDOR -> 404 (like get()). Idempotent on CANCELLED;
     * a finished analysis (COMPLETED/FAILED) -> 409. Frees the in-flight slot, publishes
     * analysis.cancel (forward-compat for detectors), and closes the SSE stream as CANCELLED.
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

        // TODO(week 6, D6 race fix): this read-modify-write isn't atomic — a detector result could
        // land between findById and save. Replace with an atomic UPDATE ... WHERE status IN
        // ('PENDING','PROCESSING') RETURNING *. With dummy ML the window is large but low-risk.
        a.setStatus(AnalysisStatus.CANCELLED);
        repository.save(a);
        cache.evictById(id);
        backpressure.release();

        String correlationId = MDC.get("correlationId");
        rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.Q_CANCEL,
                Map.of("analysis_id", id.toString(),
                        "correlation_id", correlationId != null ? correlationId : ""));

        pushTerminalAfterCommit(id, a); // SSE: {status: CANCELLED} + close the stream
        return AnalysisResponse.from(a);
    }

    public void handleResult(Map<String, Object> payload) {
        UUID id = UUID.fromString((String) payload.get("analysis_id"));
        String source = (String) payload.get("source"); // "video" | "audio"
        String status = (String) payload.get("status"); // "COMPLETED" | "FAILED"

        if (idempotency.alreadyProcessed(id, source)) {
            log.info("Duplicate result for {}/{}, skipping (idempotency)", id, source);
            return;
        }
        markProcessedAfterCommit(id, source); // recorded only if this tx commits — retry-safe

        Analysis a = repository.findById(id).orElseThrow();

        // Idempotency — a late/duplicate result on an already-terminal analysis (incl. CANCELLED) is
        // ignored, so it can neither overwrite the state nor double-release the backpressure slot.
        if (a.getStatus().isTerminal()) {
            log.warn("Late/duplicate result for {} (status={}, source={}), ignoring", id, a.getStatus(), source);
            return;
        }

        if ("FAILED".equals(status)) {
            a.setStatus(AnalysisStatus.FAILED);
            a.setErrorMessage(extractError(payload));
            repository.save(a);
            cache.evictById(id);
            backpressure.release();
            pushTerminalAfterCommit(id, a);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) payload.get("result");
        BigDecimal prob = new BigDecimal(result.get("prob_fake").toString());
        if ("video".equals(source)) a.setVideoProb(prob);
        if ("audio".equals(source)) a.setAudioProb(prob);

        boolean needV = a.getType() == AnalysisType.VIDEO || a.getType() == AnalysisType.FULL;
        boolean needA = a.getType() == AnalysisType.AUDIO || a.getType() == AnalysisType.FULL;
        boolean done = (!needV || a.getVideoProb() != null)
                    && (!needA || a.getAudioProb() != null);

        if (done) {
            BigDecimal finalProb = aggregate(a.getVideoProb(), a.getAudioProb()); // 0.6V + 0.4A
            a.setStatus(AnalysisStatus.COMPLETED);
            a.setVerdict(finalProb.compareTo(THRESHOLD) > 0 ? "FAKE" : "REAL");
            a.setConfidence(finalProb.subtract(THRESHOLD).abs().multiply(TWO));
        }
        repository.save(a);
        // Evict so a GET right after completion returns the new state, not the cached PENDING.
        cache.evictById(id);
        if (done) {
            backpressure.release();
            pushTerminalAfterCommit(id, a);
        }

        // TODO(week 6, D6 race fix): replace findById + save with an atomic
        // UPDATE ... WHERE id = :id AND status IN ('PENDING','PROCESSING') RETURNING *.
        // With dummy ML (~2s sleep) the race is unlikely; with real ML both replies arrive
        // within ms and last-write-wins drops one of videoProb/audioProb.
    }

    // Dead-lettered message: the DLQ consumer asks us to fail the analysis.
    public void failFromDlq(UUID id, String reason) {
        transitionToFailed(id, "dead-letter: " + reason);
    }

    // Stuck-job recovery asks us to fail an analysis that hasn't progressed past its threshold.
    public void failStuck(UUID id, long thresholdSeconds) {
        transitionToFailed(id, "stuck > " + thresholdSeconds + "s, auto-failed by recovery");
    }

    // Shared terminal-failure transition. The isTerminal() guard makes it idempotent and ensures
    // exactly one release() per analysis even if (e.g.) a late result and recovery both fire.
    private void transitionToFailed(UUID id, String errorMessage) {
        Analysis a = repository.findById(id).orElse(null);
        if (a == null) {
            log.warn("fail requested for unknown analysis {}", id);
            return;
        }
        if (a.getStatus().isTerminal()) {
            log.info("fail requested for already-terminal {} ({}), ignoring", id, a.getStatus());
            return;
        }
        a.setStatus(AnalysisStatus.FAILED);
        a.setErrorMessage(errorMessage);
        repository.save(a);
        cache.evictById(id);
        backpressure.release();
        pushTerminalAfterCommit(id, a);
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
