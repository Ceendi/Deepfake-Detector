package com.deepfake.orchestrator.sse;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.deepfake.orchestrator.dto.sse.AnalysisProgressEvent;
import com.deepfake.orchestrator.dto.sse.AnalysisResultEvent;
import com.deepfake.orchestrator.exception.TooManyStreamsException;

/** Single-instance routing; each admitted HTTP connection owns one bounded mailbox and writer. */
@Component
public class AnalysisStreamRegistry {
    private final Object admission = new Object();
    private final Map<UUID, Set<Session>> byId = new HashMap<>();
    private final Map<SseEmitter, Session> sessions = new IdentityHashMap<>();
    private final Map<String, Integer> users = new HashMap<>();
    private final Map<UUID, Integer> analyses = new HashMap<>();
    private final SseLimits limits;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("sse-watchdog").factory());

    public AnalysisStreamRegistry() { this(SseLimits.defaults()); }
    @Autowired public AnalysisStreamRegistry(SseLimits limits) { this.limits = limits; }
    @PostConstruct public void start() {
        clock.scheduleAtFixedRate(this::heartbeat, limits.heartbeat().toMillis(), limits.heartbeat().toMillis(), TimeUnit.MILLISECONDS);
        clock.scheduleAtFixedRate(this::observe, 1, 1, TimeUnit.SECONDS);
    }
    @PreDestroy public void shutdown() {
        clock.shutdownNow();
        snapshot().forEach(Session::close);
    }

    public SseEmitter register(UUID id, String owner) {
        synchronized (admission) {
            if (sessions.size() >= limits.globalConnections()
                    || users.getOrDefault(owner, 0) >= limits.userConnections()
                    || analyses.getOrDefault(id, 0) >= limits.analysisConnections()) throw new TooManyStreamsException();
            SseEmitter emitter = createEmitter();
            Session session = new Session(id, owner, emitter);
            sessions.put(emitter, session);
            users.merge(owner, 1, Integer::sum); analyses.merge(id, 1, Integer::sum);
            byId.computeIfAbsent(id, key -> new HashSet<>()).add(session);
            emitter.onCompletion(session::physicalCompletion);
            emitter.onError(error -> session.close());
            emitter.onTimeout(session::close);
            return emitter;
        }
    }
    protected SseEmitter createEmitter() { return new SseEmitter(limits.lifetime().toMillis()); }
    Session session(SseEmitter emitter) { synchronized (admission) { return sessions.get(emitter); } }
    private List<Session> snapshot() { synchronized (admission) { return List.copyOf(sessions.values()); } }
    private List<Session> subscribers(UUID id, boolean detach) {
        synchronized (admission) {
            Set<Session> set = detach ? byId.remove(id) : byId.get(id);
            return set == null ? List.of() : List.copyOf(set);
        }
    }
    private void detach(Session session) {
        synchronized (admission) {
            Set<Session> set = byId.get(session.id);
            if (set != null && set.remove(session) && set.isEmpty()) byId.remove(session.id);
        }
    }
    private void release(Session session) {
        detach(session);
        synchronized (admission) {
            if (sessions.remove(session.emitter) == null) return;
            users.compute(session.owner, (key, n) -> n == 1 ? null : n - 1);
            analyses.compute(session.id, (key, n) -> n == 1 ? null : n - 1);
        }
    }
    public void sendProgress(UUID id, AnalysisProgressEvent event) {
        // Keep advisory retention bounded even for an arbitrary producer stage/status string.
        if (event == null || event.stage() != null && event.stage().length() > 512
                || event.status() != null && event.status().length() > 32
                || event.analysisId() == null || event.analysisId().length() > 36) return;
        subscribers(id, false).forEach(session -> session.progress(event));
    }
    public void sendResult(UUID id, AnalysisResultEvent event) {
        subscribers(id, true).forEach(session -> session.terminal(event));
    }
    public void sendResult(UUID id, SseEmitter emitter, AnalysisResultEvent event) {
        Session session = session(emitter);
        if (session != null && session.id.equals(id)) { detach(session); session.terminal(event); }
    }
    public void discard(UUID id, SseEmitter emitter) {
        Session session = session(emitter);
        if (session != null && session.id.equals(id)) {
            session.close();
            // A failed fresh snapshot never returns this emitter to MVC, hence no physical request exists.
            synchronized (session) { if (session.bound) return; }
            session.physicalCompletion();
        }
    }
    public void complete(UUID id) { subscribers(id, true).forEach(Session::close); }
    public void heartbeat() { subscribersSnapshot().forEach(Session::heartbeat); }
    private List<Session> subscribersSnapshot() {
        synchronized (admission) { return byId.values().stream().flatMap(Collection::stream).toList(); }
    }
    public void observe() { snapshot().forEach(Session::observe); }
    // Package-private observations for fault acceptance; never expose an HTTP administration surface.
    int admitted() { synchronized (admission) { return sessions.size(); } }
    long writing() { return writingLongerThan(java.time.Duration.ZERO); }
    long writingLongerThan(java.time.Duration duration) {
        return snapshot().stream().filter(session -> { synchronized (session) {
            return session.writeSince != 0 && System.nanoTime()-session.writeSince >= duration.toNanos();
        }}).count();
    }

    final class Session {
        final UUID id; final String owner; final SseEmitter emitter;
        final long created = System.nanoTime();
        AnalysisProgressEvent video, audio;
        AnalysisResultEvent result;
        boolean heartbeat, closing, bound, initialized, initializationFailure, physical, writerStarted, writerExited, released;
        long writeSince;
        Session(UUID id, String owner, SseEmitter emitter) { this.id=id; this.owner=owner; this.emitter=emitter; }
        synchronized void bind() { bound = true; }
        synchronized void initialized() { initialized = true; }
        void ready() {
            synchronized (this) {
                if (!initialized || initializationFailure || physical || writerStarted) return;
                if (!closing && result == null) heartbeat = true;
                writerStarted = true;
            }
            try { Thread.ofVirtual().name("sse-" + id).start(this::drain); }
            catch (RuntimeException failure) { close(); exited(); throw failure; }
        }
        synchronized void progress(AnalysisProgressEvent event) {
            if (closing || result != null || physical) return;
            if ("video".equals(event.source())) video=event;
            else if ("audio".equals(event.source())) audio=event;
            else return;
            notifyAll();
        }
        synchronized void terminal(AnalysisResultEvent event) {
            if (closing || physical || result != null) return;
            result=event; video=null; audio=null; heartbeat=false; notifyAll();
        }
        synchronized void heartbeat() { if (!closing && result == null && !physical) { heartbeat=true; notifyAll(); } }
        void close() {
            synchronized (this) { closing=true; video=null; audio=null; heartbeat=false; notifyAll(); }
            detach(this);
        }
        void initializationFailed() { synchronized (this) { initializationFailure=true; } close(); }
        void physicalCompletion() {
            synchronized (this) { physical=true; closing=true; video=null; audio=null; result=null; heartbeat=false; notifyAll(); }
            detach(this); releaseIfFinished();
        }
        void exited() { synchronized (this) { writerExited=true; writeSince=0; } releaseIfFinished(); }
        void releaseIfFinished() {
            synchronized (this) {
                if (released || !physical || writerStarted && !writerExited) return;
                released=true;
            }
            release(this);
        }
        void observe() {
            boolean stale;
            synchronized (this) {
                long now=System.nanoTime();
                stale=now-created >= limits.lifetime().toNanos()
                        || writeSince != 0 && now-writeSince >= limits.slowWrite().toNanos();
            }
            if (stale) close(); // Observe and isolate; this is not a promise of forced socket abortion.
        }
        void drain() {
            try {
                while (true) {
                    Object item;
                    synchronized (this) {
                        while (!physical && !closing && result == null && video == null && audio == null && !heartbeat) wait();
                        if (physical) return;
                        if (closing) item=null;
                        else if (result != null) { item=result; result=null; closing=true; }
                        else if (video != null) { item=video; video=null; }
                        else if (audio != null) { item=audio; audio=null; }
                        else { item="hb"; heartbeat=false; }
                        writeSince=System.nanoTime();
                    }
                    if (item == null) { emitter.complete(); return; }
                    if (item instanceof AnalysisResultEvent terminal) {
                        emitter.send(SseEmitter.event().name("result").data(terminal));
                        emitter.complete(); return;
                    }
                    if (item instanceof AnalysisProgressEvent progress)
                        emitter.send(SseEmitter.event().name("progress").data(progress));
                    else emitter.send(SseEmitter.event().comment("hb"));
                    synchronized (this) { writeSince=0; }
                }
            } catch (IOException failure) {
                // Servlet error dispatch completes the physical request; never recycle its permit early.
                close();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt(); close();
            } catch (RuntimeException failure) {
                close(); emitter.completeWithError(failure);
            } finally { exited(); }
        }
    }
}
