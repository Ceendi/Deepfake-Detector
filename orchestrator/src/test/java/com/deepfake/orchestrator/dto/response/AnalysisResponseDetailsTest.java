package com.deepfake.orchestrator.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Test
    void gradcamKeysAreTranslatedToArtifactEndpointUrls() {
        UUID id = UUID.randomUUID();
        Analysis a = analysis(null, Map.of(
                "modelVersion", "v1.2.0-fast",
                "gradcamKeys", List.of(id + "/audio/gradcam.png", id + "/legacy.png")));
        a.setId(id);

        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) AnalysisResponse.from(a).details().get("audio");

        assertThat(audio).containsEntry("gradcamUrls", List.of(
                "/api/analysis/" + id + "/artifacts/audio/gradcam.png",
                "/api/analysis/" + id + "/artifacts/audio/legacy.png"));
        // keys stay for audit; the entity's stored map is not mutated by the view
        assertThat(audio).containsKey("gradcamKeys");
        assertThat(a.getAudioDetails()).doesNotContainKey("gradcamUrls");
    }

    private static Analysis analysis(Map<String, Object> video, Map<String, Object> audio) {
        return Analysis.builder().userId("alice").fileId("f").fileKey("k")
                .type(AnalysisType.FULL).videoDetails(video).audioDetails(audio).build();
    }
}
