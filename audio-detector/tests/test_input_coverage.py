"""Sample-aware production analyze/consumer tests without model checkpoints."""
import json
import math
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import numpy as np
import pika
import pytest

from src.score_contract import NoSpeechError, normalize_audio_score

WIRE_FAILURE = json.loads((Path(__file__).resolve().parents[2] /
    "orchestrator/src/test/resources/contracts/audio-no-speech-result.json").read_text())


class NumericTensor(np.ndarray):
    """Retain actual waveform samples through the small tensor operations analyze uses."""

    def unsqueeze(self, axis):
        return np.expand_dims(self, axis)

    def numpy(self):
        return np.asarray(self)


@pytest.fixture
def analyzer(inference_module, monkeypatch):
    module = inference_module
    torch = SimpleNamespace(
        sqrt=np.sqrt, mean=np.mean, no_grad=nullcontext,
        sigmoid=lambda value: np.float64(1 / (1 + np.exp(-value))),
        nn=SimpleNamespace(functional=SimpleNamespace(
            pad=lambda tensor, padding: np.pad(tensor, padding).view(NumericTensor))),
    )
    monkeypatch.setattr(module, "torch", torch)
    instance = module.AudioInference.__new__(module.AudioInference)
    inputs = []
    logit = math.log(.4 / .6)

    def mel(tensor):
        inputs.append(tensor.numpy().copy())
        return logit

    def w2v2(outputs, feed):
        inputs.append(feed["input_values"].copy())
        return [[[logit]]]

    instance.mel_module = MagicMock(side_effect=mel)
    instance.w2v2_session = MagicMock()
    instance.w2v2_session.run.side_effect = w2v2
    instance.generate_gradcam = MagicMock()
    instance._check_duration = MagicMock()
    instance._extract_audio = MagicMock(side_effect=lambda path, sr, out: Path(out).write_bytes(b"wav"))

    def load(samples):
        instance._load_wav = MagicMock(return_value=np.asarray(samples).view(NumericTensor))
        return instance, inputs

    return load


@pytest.mark.parametrize("mode,threshold", [("fast", .3049), ("accurate", .3)])
@pytest.mark.parametrize("length,starts", [
    (4000, [0]), (16000, [0]), (16001, [0, 1]),
    (23840, [0, 7840]), (23919, [0, 7919]), (24000, [0, 8000]),
    (32000, [0, 8000, 16000]), (39840, [0, 8000, 16000, 23840]),
])
def test_actual_model_inputs_cover_all_samples_and_keep_regular_windows(
        analyzer, tmp_path, mode, threshold, length, starts):
    samples = .02 + np.arange(length) * 1e-7
    instance, inputs = analyzer(samples)
    path = tmp_path / "input"
    result = instance.analyze(str(path), mode=mode)
    assert len(inputs) == len(starts)
    covered = np.zeros(length, dtype=bool)
    segments = result["metadata"]["segment_predictions"]
    for model_input, segment, start in zip(inputs, segments, starts, strict=True):
        end = min(start + 16000, length)
        assert model_input.shape == (1, 16000)
        np.testing.assert_array_equal(model_input[0, :end-start], samples[start:end])
        np.testing.assert_array_equal(model_input[0, end-start:], 0)
        covered[start:end] = True
        assert segment["start_time"] == start / 16000
        assert segment["end_time"] == end / 16000
        assert segment["end_time"] <= result["metadata"]["duration_seconds"]
    assert covered.all()
    assert result["metadata"]["duration_seconds"] == length / 16000
    assert result["metadata"]["segments_processed"] == len(starts)
    assert result["metadata"]["raw_prob_fake"] == pytest.approx(.4)
    assert result["prob_fake"] == normalize_audio_score(.4, threshold)
    assert result["verdict"] == "FAKE"
    assert result["confidence"] == round(2 * abs(result["prob_fake"] - .5), 4)
    assert result["metadata"]["score_contract"] == "audio-threshold-v1"
    assert not Path(f"{path}_16k.wav").exists()
    if mode == "fast":
        assert instance.mel_module.call_count == len(starts)
        instance.w2v2_session.run.assert_not_called()
    else:
        assert instance.w2v2_session.run.call_count == len(starts)
        instance.mel_module.assert_not_called()


@pytest.mark.parametrize("mode", ["fast", "accurate"])
def test_tail_only_audible_signal_reaches_inference(analyzer, tmp_path, mode):
    samples = np.zeros(23840)
    samples[16000:] = .01
    instance, inputs = analyzer(samples)
    result = instance.analyze(str(tmp_path / "tail"), mode=mode)
    assert len(inputs) == 1
    np.testing.assert_array_equal(inputs[0][0], samples[7840:])
    assert result["metadata"]["segments_processed"] == 2
    segment, = result["metadata"]["segment_predictions"]
    assert (segment["start_time"], segment["end_time"]) == (.49, 1.49)


@pytest.mark.parametrize("mode", ["fast", "accurate"])
@pytest.mark.parametrize("length,amplitude", [
    (0, 0), (4000, 0), (16000, 0), (23840, 0),
    (4000, .001), (16000, .001), (23840, .001),
    (4000, .006),  # Padded RMS is .003: preserve the existing model-input energy gate.
])
def test_silence_quiet_and_empty_audio_fail_without_model_evidence(
        analyzer, tmp_path, mode, length, amplitude):
    instance, inputs = analyzer(np.full(length, amplitude))
    progress = MagicMock()
    path = tmp_path / "silent"
    with pytest.raises(NoSpeechError) as error:
        instance.analyze(str(path), mode=mode, progress_callback=progress)
    assert error.value.code == "NO_SPEECH"
    assert inputs == []
    instance.mel_module.assert_not_called()
    instance.w2v2_session.run.assert_not_called()
    instance.generate_gradcam.assert_not_called()
    assert not any(call.args[0] == 100 for call in progress.call_args_list)
    assert not Path(f"{path}_16k.wav").exists()
    assert not Path(f"{path}_gradcam.png").exists()


@pytest.mark.parametrize("mode", ["fast", "accurate"])
@pytest.mark.parametrize("failed_transport", [None, "publish", "ack"])
def test_actual_analyze_no_speech_publishes_confirmed_failed_before_ack(
        analyzer, monkeypatch, mode, failed_transport):
    from src import consumer

    instance, inputs = analyzer(np.zeros(23840))
    monkeypatch.setattr(consumer, "audio_inference", instance)
    redis = MagicMock()
    redis.exists.return_value = 0
    monkeypatch.setattr(consumer.runtime, "redis_client", redis)
    s3 = MagicMock()
    s3.download_file.side_effect = lambda bucket, key, path, **kw: Path(path).write_bytes(b"input")
    monkeypatch.setattr(consumer.runtime, "s3_client", s3)
    channel = MagicMock()
    if failed_transport == "ack":
        channel.basic_ack.side_effect = pika.exceptions.StreamLostError("lost before ack")
    published = []

    def confirm(**kwargs):
        if kwargs["routing_key"] != "analysis.results":
            return
        channel.basic_ack.assert_not_called()
        assert kwargs["mandatory"] is True
        assert kwargs["properties"].delivery_mode == 2
        payload = json.loads(kwargs["body"])
        published.append(payload)
        if failed_transport == "publish":
            raise pika.exceptions.NackError([])

    channel.basic_publish.side_effect = confirm
    task = {"analysis_id": WIRE_FAILURE["analysis_id"],
            "correlation_id": WIRE_FAILURE["correlation_id"],
            "file_bucket": "test", "file_key": "silent.wav", "mode": mode}
    args = (channel, MagicMock(delivery_tag=7), MagicMock(headers={}), json.dumps(task).encode())
    if failed_transport == "publish":
        with pytest.raises(pika.exceptions.NackError):
            consumer._handle_message(*args)
        channel.basic_ack.assert_not_called()
    elif failed_transport == "ack":
        with pytest.raises(pika.exceptions.StreamLostError):
            consumer._handle_message(*args)
        channel.basic_ack.assert_called_once_with(delivery_tag=7)
    else:
        consumer._handle_message(*args)
        channel.basic_ack.assert_called_once_with(delivery_tag=7)
    assert published == [WIRE_FAILURE]
    assert inputs == []
    s3.upload_file.assert_not_called()
    input_path = s3.download_file.call_args.args[2]
    assert not Path(input_path).exists()
    assert not Path(input_path).parent.exists()
