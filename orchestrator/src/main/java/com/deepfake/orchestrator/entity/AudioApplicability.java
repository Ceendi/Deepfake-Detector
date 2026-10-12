package com.deepfake.orchestrator.entity;

import java.util.Map;

/** Durable source outcome for FULL audio that supplied no model evidence. */
public final class AudioApplicability {
    private AudioApplicability() {}

    public static boolean isSupportedReason(Object reason) {
        return "NO_SPEECH".equals(reason) || "NO_AUDIO_TRACK".equals(reason);
    }

    public static Map<String, Object> notApplicable(String reason, String message) {
        if (!isSupportedReason(reason)) throw new IllegalArgumentException("Unknown audio applicability reason");
        return Map.of("status", "NOT_APPLICABLE", "reason", reason, "message", message);
    }

    public static String defaultMessage(String reason) {
        return "NO_AUDIO_TRACK".equals(reason) ? "Materiał nie zawiera ścieżki audio."
                : "Brak wystarczająco wyraźnego sygnału audio do analizy.";
    }

    public static boolean isNotApplicable(Map<?, ?> details) {
        return details != null && "NOT_APPLICABLE".equals(details.get("status"))
                && isSupportedReason(details.get("reason"));
    }
}
