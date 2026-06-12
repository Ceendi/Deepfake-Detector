package com.deepfake.orchestrator.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.deepfake.orchestrator.entity.AnalysisType;

import tools.jackson.databind.json.JsonMapper;

/** Wire format: lowercase on the wire, case-insensitive on input, unknown value rejected. */
class AnalysisModeTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serializesLowercase() {
        assertThat(json.writeValueAsString(AnalysisMode.FAST)).isEqualTo("\"fast\"");
        assertThat(json.writeValueAsString(AnalysisMode.ACCURATE)).isEqualTo("\"accurate\"");
    }

    @Test
    void deserializesCaseInsensitive() {
        assertThat(json.readValue("\"fast\"", AnalysisMode.class)).isEqualTo(AnalysisMode.FAST);
        assertThat(json.readValue("\"ACCURATE\"", AnalysisMode.class)).isEqualTo(AnalysisMode.ACCURATE);
    }

    @Test
    void rejectsUnknownValue() {
        assertThatThrownBy(() -> json.readValue("\"turbo\"", AnalysisMode.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void nullModeNormalizesToAccurate() {
        var req = new CreateAnalysisRequest("f", "k", AnalysisType.AUDIO, null);
        assertThat(req.mode()).isEqualTo(AnalysisMode.ACCURATE);
    }
}
