package com.deepfake.orchestrator.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Explicitly simulate successful MVC initialization and physical completion in non-HTTP tests. */
public final class RegistryTestHarness {
    public static void ready(AnalysisStreamRegistry registry, SseEmitter emitter) {
        var session=registry.session(emitter);
        if (session != null) { session.bind(); session.initialized(); session.ready(); }
    }
    public static void completed(AnalysisStreamRegistry registry, SseEmitter emitter) {
        var session=registry.session(emitter); if (session != null) session.physicalCompletion();
    }
    public static int admitted(AnalysisStreamRegistry registry) { return registry.admitted(); }
}
