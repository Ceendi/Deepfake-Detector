package com.deepfake.orchestrator.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps the AMQP result payload onto the per-source details JSON persisted on the analysis
 * (docs/contracts/amqp-messages.md). Top-level keys are camelCase (REST convention);
 * {@code metadata} is detector-defined and passed through as-is, except for the segment cap.
 * Stateless — instantiated directly by {@link AnalysisService}, no wiring needed.
 */
class ResultDetailsExtractor {

    // 1h of audio ≈ 7200 half-second segments (~0.5 MB JSONB); uniform stride keeps the timeline shape.
    static final int MAX_SEGMENTS = 500;

    /** Returns the details to persist, or null when the result carries nothing beyond prob_fake. */
    Map<String, Object> extract(Map<String, Object> result) {
        Map<String, Object> details = new LinkedHashMap<>();
        Object modelVersion = result.get("model_version");
        if (modelVersion != null) {
            details.put("modelVersion", modelVersion.toString());
        }
        Object confidence = result.get("confidence");
        if (confidence != null) {
            details.put("confidence", new BigDecimal(confidence.toString()));
        }
        Object verdict = result.get("verdict");
        if (verdict != null) {
            details.put("verdict", verdict.toString());
        }
        List<String> keys = gradcamKeys(result);
        if (!keys.isEmpty()) {
            details.put("gradcamKeys", keys);
        }
        Map<String, Object> metadata = cappedMetadata(result.get("metadata"));
        if (metadata != null && !metadata.isEmpty()) {
            details.put("metadata", metadata);
        }
        return details.isEmpty() ? null : details;
    }

    // Contract field only (amqp-messages.md): bare object keys, no URI scheme, no bucket prefix.
    private List<String> gradcamKeys(Map<String, Object> result) {
        if (result.get("gradcam_keys") instanceof Collection<?> keys) {
            return keys.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cappedMetadata(Object metadata) {
        if (!(metadata instanceof Map)) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) metadata);
        if (copy.get("segment_predictions") instanceof List<?> segments && segments.size() > MAX_SEGMENTS) {
            int stride = (int) Math.ceil(segments.size() / (double) MAX_SEGMENTS);
            List<Object> sampled = new ArrayList<>((segments.size() + stride - 1) / stride);
            for (int i = 0; i < segments.size(); i += stride) {
                sampled.add(segments.get(i));
            }
            copy.put("segment_predictions", sampled);
            copy.put("segment_predictions_downsampled", true);
        }
        return copy;
    }
}
