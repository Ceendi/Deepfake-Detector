package com.deepfake.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Result payload → persisted details JSON: camelCase top level, gradcam key normalization
 * (contract list, pre-rename list, legacy minio:// single url), and the segment cap that keeps
 * a long recording's JSONB bounded.
 */
class ResultDetailsExtractorTest {

    private final ResultDetailsExtractor extractor = new ResultDetailsExtractor();

    @Test
    void mapsModelVersionGradcamKeysAndMetadata() {
        Map<String, Object> details = extractor.extract(Map.of(
                "prob_fake", 0.87,
                "model_version", "v1.2.0-accurate",
                "gradcam_keys", List.of("id/audio/gradcam.png"),
                "metadata", Map.of("duration_seconds", 12.5)));

        assertThat(details)
                .containsEntry("modelVersion", "v1.2.0-accurate")
                .containsEntry("gradcamKeys", List.of("id/audio/gradcam.png"))
                .containsEntry("metadata", Map.of("duration_seconds", 12.5));
    }

    @Test
    void probOnlyResultYieldsNull() {
        assertThat(extractor.extract(Map.of("prob_fake", 0.5))).isNull();
        assertThat(extractor.extract(Map.of("prob_fake", 0.5, "gradcam_urls", List.of(),
                "metadata", Map.of()))).isNull();
    }

    @Test
    void legacyMinioUrlIsNormalizedToObjectKey() {
        Map<String, Object> details = extractor.extract(Map.of(
                "gradcam_url", "minio://analysis-artifacts/abc/gradcam.png"));

        assertThat(details).containsEntry("gradcamKeys", List.of("abc/gradcam.png"));
    }

    @Test
    void preRenameGradcamUrlsListIsAccepted() {
        Map<String, Object> details = extractor.extract(Map.of(
                "gradcam_urls", List.of("minio://analysis-artifacts/abc/frame1.png", "abc/frame2.png")));

        assertThat(details).containsEntry("gradcamKeys", List.of("abc/frame1.png", "abc/frame2.png"));
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
