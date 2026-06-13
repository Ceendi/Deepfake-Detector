package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Result payload → persisted details JSON: camelCase top level, contract-only gradcam_keys,
 * and the segment cap that keeps a long recording's JSONB bounded.
 */
class ResultDetailsExtractorTest {

    private final ResultDetailsExtractor extractor = new ResultDetailsExtractor();

    @Test
    void mapsModelVersionConfidenceVerdictGradcamKeysAndMetadata() {
        Map<String, Object> details = extractor.extract(Map.of(
                "prob_fake", 0.87,
                "verdict", "FAKE",
                "confidence", 0.74,
                "model_version", "v1.2.0-accurate",
                "gradcam_keys", List.of("id/audio/gradcam.png"),
                "metadata", Map.of("duration_seconds", 12.5)));

        assertThat(details)
                .containsEntry("modelVersion", "v1.2.0-accurate")
                .containsEntry("confidence", new BigDecimal("0.74"))
                .containsEntry("verdict", "FAKE")
                .containsEntry("gradcamKeys", List.of("id/audio/gradcam.png"))
                .containsEntry("metadata", Map.of("duration_seconds", 12.5));
    }

    @Test
    void confidenceIsPersistedAsBigDecimalAndVerdictAsString() {
        Map<String, Object> details = extractor.extract(Map.of(
                "confidence", 0.82,
                "verdict", "REAL"));

        assertThat(details.get("confidence")).isInstanceOf(BigDecimal.class);
        assertThat(details.get("confidence")).isEqualTo(new BigDecimal("0.82"));
        assertThat(details.get("verdict")).isEqualTo("REAL");
    }

    @Test
    void probOnlyResultYieldsNull() {
        assertThat(extractor.extract(Map.of("prob_fake", 0.5))).isNull();
        assertThat(extractor.extract(Map.of("prob_fake", 0.5, "gradcam_keys", List.of(),
                "metadata", Map.of()))).isNull();
    }

    @Test
    void nonContractGradcamFieldsAreIgnored() {
        // gradcam_keys is the only contract field — stray variants must not leak into details.
        Map<String, Object> details = extractor.extract(Map.of(
                "model_version", "v1",
                "gradcam_url", "minio://analysis-artifacts/abc/gradcam.png",
                "gradcam_urls", List.of("abc/frame1.png")));

        assertThat(details).doesNotContainKey("gradcamKeys");
    }

    @Test
    void longSegmentListIsDownsampledToCap() {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < 7200; i++) {
            segments.add(Map.of("start_time", i * 0.5, "prob_fake", 0.1));
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("segment_predictions", segments);
        metadata.put("insights", List.of("ok"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                extractor.extract(Map.of("metadata", metadata)).get("metadata");

        assertThat((List<?>) out.get("segment_predictions"))
                .hasSizeLessThanOrEqualTo(ResultDetailsExtractor.MAX_SEGMENTS)
                .first().isEqualTo(segments.getFirst()); // uniform stride keeps the timeline start
        assertThat(out).containsEntry("segment_predictions_downsampled", true)
                .containsEntry("insights", List.of("ok"));
    }

    @Test
    void shortSegmentListIsLeftUntouched() {
        Map<String, Object> metadata = Map.of(
                "segment_predictions", List.of(Map.of("prob_fake", 0.9)));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                extractor.extract(Map.of("metadata", metadata)).get("metadata");

        assertThat(out).isEqualTo(metadata).doesNotContainKey("segment_predictions_downsampled");
    }
}
