"""Audio decision-scale contract; this is threshold alignment, not calibration."""

import math

SCORE_CONTRACT = "audio-threshold-v1"


def normalize_audio_score(raw_score: float, threshold: float) -> float:
    """Map [0, threshold, 1] to [0, 0.5, 1], preserving the strict decision after rounding."""
    if not math.isfinite(threshold) or not 0 < threshold < 1:
        raise ValueError("Audio threshold must be finite and strictly between 0 and 1")
    if not math.isfinite(raw_score) or not 0 <= raw_score <= 1:
        raise ValueError("Audio score must be finite and between 0 and 1")
    if raw_score <= threshold:
        return round(0.5 * (raw_score / threshold), 4)
    normalized = 0.5 + 0.5 * (raw_score - threshold) / (1 - threshold)
    # Four decimal places are also the PostgreSQL probability column's precision.
    # A positive margin must never round back to the REAL boundary.
    return max(0.5001, round(normalized, 4))


def generate_insights(segment_predictions: list[dict], probability: float) -> list[str]:
    """Heuristic descriptions of published decision scores, not independent model evidence."""
    if not segment_predictions:
        return ["Brak danych do analizy odcinkowej."]
    insights = []
    if probability > 0.85:
        insights.append("Całe nagranie wykazuje wysokie wskaźniki syntezy AI według modelu.")
    elif probability < 0.15:
        insights.append("Model wskazuje na niski poziom cech syntezy AI w nagraniu.")
    fake_segments = [s for s in segment_predictions if s["prob_fake"] > 0.5]
    if fake_segments and probability <= 0.85:
        clusters = []
        current_cluster = [fake_segments[0]]
        for segment in fake_segments[1:]:
            if segment["start_time"] - current_cluster[-1]["start_time"] <= 0.6:
                current_cluster.append(segment)
            else:
                clusters.append(current_cluster)
                current_cluster = [segment]
        clusters.append(current_cluster)
        biggest_cluster = max(clusters, key=len)
        start_time = biggest_cluster[0]["start_time"]
        end_time = biggest_cluster[-1]["end_time"]
        if len(biggest_cluster) >= 3:
            insights.append(
                f"Najwyższe stężenie cech deepfake występuje w fragmencie nagrania "
                f"od {start_time:.1f}s do {end_time:.1f}s.")
        elif len(fake_segments) <= 3 and probability <= 0.5:
            peak = max(fake_segments, key=lambda s: s["prob_fake"])
            insights.append(
                f"Nagranie ma niski wynik ogólny, jednak model wskazuje podejrzany fragment "
                f"w okolicy {peak['start_time']:.1f}s - {peak['end_time']:.1f}s.")
    return insights or ["Analiza wykazuje niejednoznaczne cechy - zalecana dodatkowa weryfikacja."]


def build_audio_result(raw_segments: list[dict], threshold: float, mode: str) -> dict:
    """Pool the highest 30% of raw window scores, then map once to the shared scale.

    Window scores stay unrounded until pooling. Empty speech keeps the existing
    neutral fallback (the model threshold), hence REAL with zero confidence.
    """
    segments = [dict(segment, prob_fake=normalize_audio_score(
        segment["raw_prob_fake"], threshold)) for segment in raw_segments]
    scores = sorted((s["raw_prob_fake"] for s in raw_segments), reverse=True)
    top_k = max(1, int(len(scores) * 0.3))
    raw_score = math.fsum(scores[:top_k]) / top_k if scores else threshold
    probability = normalize_audio_score(raw_score, threshold)
    return {
        "prob_fake": probability,
        "verdict": "FAKE" if probability > 0.5 else "REAL",
        "confidence": round(abs(probability - 0.5) * 2, 4),
        "model_version": f"v1.3.0-{mode}",
        "metadata": {
            "score_contract": SCORE_CONTRACT,
            "raw_prob_fake": raw_score,
            "threshold_used": threshold,
            "mode_used": mode,
            "segment_predictions": segments,
            "insights": generate_insights(segments, probability),
        },
    }
