package com.deepfake.orchestrator.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.deepfake.orchestrator.entity.Analysis;
import com.deepfake.orchestrator.entity.AnalysisType;

/** details groups the per-source columns under video/audio keys; null until a detector reports. */
class AnalysisResponseDetailsTest {

    @Test
    void detailsIsNullWhenNoDetectorReported() {
        assertThat(AnalysisResponse.from(analysis(null, null)).details()).isNull();
    }

    @Test
    void detailsCarriesOnlyTheSourcesThatReported() {
        Map<String, Object> audio = Map.of("modelVersion", "v1.2.0-fast");

        AnalysisResponse partial = AnalysisResponse.from(analysis(null, audio));

        assertThat(partial.details()).containsOnlyKeys("audio").containsEntry("audio", audio);
    }

    @Test
    void detailsCarriesBothSourcesForFull() {
        Map<String, Object> video = Map.of("modelVersion", "v1.0.0");
        Map<String, Object> audio = Map.of("modelVersion", "v1.2.0-accurate");

        AnalysisResponse full = AnalysisResponse.from(analysis(video, audio));

        assertThat(full.details())
                .containsEntry("video", video)
                .containsEntry("audio", audio);
    }

    private static Analysis analysis(Map<String, Object> video, Map<String, Object> audio) {
        return Analysis.builder().userId("alice").fileId("f").fileKey("k")
                .type(AnalysisType.FULL).videoDetails(video).audioDetails(audio).build();
    }
}
