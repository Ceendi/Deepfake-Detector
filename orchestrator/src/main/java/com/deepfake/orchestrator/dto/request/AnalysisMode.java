package com.deepfake.orchestrator.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Audio model selection: FAST = lightweight spectrogram model, ACCURATE = Wav2Vec2 waveform
 * analysis (slower, robust to recent generators). Wire format is lowercase ("fast"/"accurate")
 * on both REST and AMQP — see docs/contracts/amqp-messages.md.
 */
public enum AnalysisMode {
    FAST,
    ACCURATE;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    // Case-insensitive parse; an unknown value surfaces as 400 MALFORMED_REQUEST (same as AnalysisType).
    @JsonCreator
    public static AnalysisMode fromWire(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
