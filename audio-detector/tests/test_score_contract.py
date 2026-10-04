"""Lightweight regression of the decision scale, inference wiring and AMQP producer."""
import json
import math
from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
import pytest

from src.score_contract import build_audio_result, generate_insights, normalize_audio_score

FIXTURES = json.loads((Path(__file__).resolve().parents[2] /
    "orchestrator/src/test/resources/contracts/audio-score-results.json").read_text())


@pytest.mark.parametrize("case", FIXTURES, ids=lambda case: case["label"])
def test_shared_wire_fixture_and_producer(case, monkeypatch):
    from src import consumer
    result = build_audio_result([{
        "start_time": 0.0, "end_time": 1.0, "raw_prob_fake": case["raw_score"]
    }], case["threshold"], case["mode"])
    inference = MagicMock()
    inference.analyze.return_value = result
    monkeypatch.setattr(consumer, "audio_inference", inference)
    monkeypatch.setattr(consumer, "s3_client", MagicMock())
    redis = MagicMock()
    redis.exists.return_value = 0
    redis.set.return_value = True
    monkeypatch.setattr(consumer, "redis_client", redis)
    channel = MagicMock()
    task = {"analysis_id": case["payload"]["analysis_id"],
                "correlation_id": case["payload"]["correlation_id"],
                "file_bucket": "test", "file_key": "audio.wav", "mode": case["mode"]}
    consumer._handle_message(channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
                             json.dumps(task).encode())
    published = [json.loads(call.kwargs["body"]) for call in channel.basic_publish.call_args_list
                 if call.kwargs["routing_key"] == "analysis.results"]
    assert published == [case["payload"]]
    channel.basic_ack.assert_called_once_with(delivery_tag=7)
    assert inference.analyze.call_args.kwargs["mode"] == case["mode"]
    probability = result["prob_fake"]
    assert (probability > 0.5) == (case["raw_score"] > case["threshold"])
    assert result["verdict"] == ("FAKE" if probability > 0.5 else "REAL")
    assert result["confidence"] == round(abs(probability - 0.5) * 2, 4)
    assert round(probability, 4) == probability


@pytest.mark.parametrize("threshold", [0.3049, 0.3, 0.1, 0.9, math.nextafter(0.0, 1.0)])
def test_endpoints_monotonicity_and_rounding(threshold):
    scores = sorted([i / 10000 for i in range(10001)] +
                    [math.nextafter(threshold, 0), threshold, math.nextafter(threshold, 1)])
    probabilities = [normalize_audio_score(score, threshold) for score in scores]
    assert probabilities == sorted(probabilities)
    assert probabilities[0] == 0
    assert probabilities[-1] == 1
    assert normalize_audio_score(threshold, threshold) == 0.5
    assert all((p > 0.5) == (s > threshold) for s, p in zip(scores, probabilities))


@pytest.mark.parametrize("raw,threshold", [(float("nan"), .3), (float("inf"), .3),
    (-.1, .3), (1.1, .3), (.4, 0), (.4, 1), (.4, float("nan"))])
def test_invalid_configuration_or_model_score_is_rejected(raw, threshold):
    with pytest.raises(ValueError):
        normalize_audio_score(raw, threshold)


def test_pooling_uses_raw_scores_before_mapping_and_rounding():
    raw = [.1, .2, .3, .4, .5, .6, .7, .8, .9, 1.]
    result = build_audio_result([{"start_time": i*.5, "end_time": i*.5+1, "raw_prob_fake": s}
                                for i, s in enumerate(raw)], .3, "accurate")
    assert result["metadata"]["raw_prob_fake"] == pytest.approx(.9)
    assert result["prob_fake"] == normalize_audio_score(result["metadata"]["raw_prob_fake"], .3)
    assert result["metadata"]["segment_predictions"][3]["prob_fake"] > .5
    assert result["metadata"]["segment_predictions"][3]["raw_prob_fake"] == .4


@pytest.mark.parametrize("threshold", [.3049, .3, math.nextafter(0.0, 1.0)])
def test_empty_speech_is_neutral_with_no_segment_evidence(threshold):
    result = build_audio_result([], threshold, "fast")
    assert (result["prob_fake"], result["verdict"], result["confidence"]) == (.5, "REAL", 0)
    assert result["metadata"]["insights"] == ["Brak danych do analizy odcinkowej."]


def test_insights_use_the_published_scale_and_strict_boundary():
    at_threshold = [{"start_time": i*.5, "end_time": i*.5+1, "prob_fake": .5} for i in range(3)]
    assert "niejednoznaczne" in generate_insights(at_threshold, .5)[0]
    above = [dict(s, prob_fake=.5001, raw_prob_fake=.300000001) for s in at_threshold]
    assert "od 0.0s do 2.0s" in generate_insights(above, .5001)[0]


@pytest.mark.parametrize("mode,threshold", [("fast", .3049), ("accurate", .3)])
@pytest.mark.parametrize("raw_score", [.1, .4, .9])
def test_real_analyze_path_selects_model_and_builds_contract(
        inference_module, monkeypatch, tmp_path, mode, threshold, raw_score):
    module = inference_module
    monkeypatch.setattr(module, "np", np)
    instance = module.AudioInference.__new__(module.AudioInference)
    instance._extract_audio = MagicMock()
    waveform = MagicMock()
    waveform.__len__.return_value = 16000
    instance._load_wav = MagicMock(return_value=waveform)
    instance.generate_gradcam = MagicMock()
    instance.mel_module = MagicMock()
    instance.w2v2_session = MagicMock()
    instance.w2v2_session.run.return_value = [[[math.log(raw_score / (1 - raw_score))]]]
    monkeypatch.setattr(module.torch, "sqrt", MagicMock(return_value=.1))
    monkeypatch.setattr(module.torch, "sigmoid", MagicMock(
        return_value=MagicMock(item=MagicMock(return_value=raw_score))))
    result = instance.analyze(str(tmp_path / "input"), mode=mode)
    assert result["prob_fake"] == normalize_audio_score(raw_score, threshold)
    assert result["metadata"]["threshold_used"] == threshold
    assert result["metadata"]["mode_used"] == mode
    assert result["metadata"]["raw_prob_fake"] == pytest.approx(raw_score)
    assert result["metadata"]["segment_predictions"][0]["prob_fake"] == result["prob_fake"]
    if mode == "fast":
        instance.mel_module.assert_called_once()
        instance.w2v2_session.run.assert_not_called()
    else:
        instance.w2v2_session.run.assert_called_once()
        instance.mel_module.assert_not_called()
