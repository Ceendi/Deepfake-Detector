package com.deepfake.orchestrator.entity;

import java.util.Map;

/** Durable source outcome for FULL audio that supplied no model evidence. */
public final class AudioApplicability {
    private AudioApplicability() {}

    public static Map<String, Object> noSpeech(String message) {
        return Map.of("status", "NOT_APPLICABLE", "reason", "NO_SPEECH", "message", message);
    }

    public static boolean isNoSpeech(Map<String, Object> details) {
        return details != null && "NOT_APPLICABLE".equals(details.get("status"))
                && "NO_SPEECH".equals(details.get("reason"));
    }
}
