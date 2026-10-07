package com.deepfake.orchestrator.sse;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;

/**
 * Holds the live SSE emitters keyed by analysisId and owns their lifecycle (register / send /
 * complete / cleanup / heartbeat), keeping that concern out of the business service. Emitters are
 * in-memory and instance-bound — fine for the single-instance orchestrator (MVP); a multi-replica
 * setup would need a backplane (V2).
 */
@Component
public class AnalysisStreamRegistry {

    private static final long TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private final Map<UUID, Collection<SseEmitter>> byId = new ConcurrentHashMap<>();

    public SseEmitter register(UUID id) {
        SseEmitter emitter = createEmitter();
        byId.compute(id, (key, emitters) -> {
            if (emitters == null) emitters = new CopyOnWriteArrayList<>();
            emitters.add(emitter);
            return emitters;
        });
        emitter.onCompletion(() -> remove(id, emitter));
        emitter.onTimeout(() -> { emitter.complete(); remove(id, emitter); }); // The client catches up when the stream closes.
        emitter.onError(e -> remove(id, emitter));
        return emitter;
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(TIMEOUT_MS);
    }

    public void sendProgress(UUID id, AnalysisProgressEvent event) {
        send(id, "progress", event);
    }

    public void sendResult(UUID id, AnalysisResultEvent event) {
        // Detach exactly the current subscribers before sending/closing. A later subscriber
        // stays registered and receives its own committed replay in openStream.
        Collection<SseEmitter> emitters = byId.remove(id);
        if (emitters != null) emitters.forEach(e -> finish(id, e, event));
    }

    /** Replay only to the subscribing emitter, without affecting other subscribers. */
    public void sendResult(UUID id, SseEmitter emitter, AnalysisResultEvent event) {
        remove(id, emitter);
        finish(id, emitter, event);
    }

    public void discard(UUID id, SseEmitter emitter) {
        remove(id, emitter);
        emitter.complete();
    }

    private void finish(UUID id, SseEmitter emitter, AnalysisResultEvent event) {
        deliver(id, emitter, SseEmitter.event().name("result").data(event));
        emitter.complete();
    }

    /** Send the terminal event(s) already buffered, then close every emitter for this analysis. */
    public void complete(UUID id) {
        Collection<SseEmitter> emitters = byId.remove(id);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    /** Comments keep idle connections alive through proxies until the first real event arrives. */
    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        byId.forEach((id, emitters) ->
                emitters.forEach(e -> deliver(id, e, SseEmitter.event().comment("hb"))));
    }

    private void send(UUID id, String name, Object data) {
        Collection<SseEmitter> emitters = byId.get(id);
        if (emitters == null) {
            return; // no open stream for this analysis — state still lives in DB/Redis
        }
        emitters.forEach(e -> deliver(id, e, SseEmitter.event().name(name).data(data)));
    }

    private void deliver(UUID id, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException ex) {
            // client gone (IOException) or emitter already completed by a concurrent terminal
            // event (IllegalStateException) — either way it's dead, drop it.
            remove(id, emitter);
        }
    }

    private void remove(UUID id, SseEmitter emitter) {
        byId.computeIfPresent(id, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
