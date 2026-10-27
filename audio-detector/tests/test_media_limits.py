"""Duration guards run before decode/allocation, without production checkpoints."""
import json
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest


def instance(module):
    return module.AudioInference.__new__(module.AudioInference)


def test_long_container_is_rejected_before_extract_or_load(inference_module, monkeypatch):
    module = inference_module
    monkeypatch.setattr(module.subprocess, "run", MagicMock(return_value=SimpleNamespace(
        returncode=0, stdout=b'{"format":{"duration":"1800.001"}}')))
    analyzer = instance(module)
    analyzer._extract_audio = MagicMock()
    analyzer._load_wav = MagicMock()
    with pytest.raises(Exception) as error:
        analyzer.analyze("/private/input.mp4")
    assert getattr(error.value, "code", None) == "MEDIA_TOO_LONG"
    assert "/private" not in str(error.value)
    analyzer._extract_audio.assert_not_called()
    analyzer._load_wav.assert_not_called()


def test_waveform_header_is_checked_before_read(inference_module, monkeypatch):
    module = inference_module
    audio = MagicMock(frames=1800 * 16000 + 1, samplerate=16000)
    monkeypatch.setattr(module, "sf", MagicMock())
    module.sf.SoundFile.return_value.__enter__.return_value = audio
    with pytest.raises(Exception) as error:
        instance(module)._load_wav("private.wav")
    assert getattr(error.value, "code", None) == "MEDIA_TOO_LONG"
    audio.read.assert_not_called()
    module.sf.SoundFile.return_value.__exit__.assert_called_once()


def test_extraction_is_bounded_and_disables_video(inference_module, monkeypatch):
    run = MagicMock(return_value=SimpleNamespace(returncode=0, stderr=b"", stdout=b'{"streams":[{"index":0}]}'))
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    instance(inference_module)._extract_audio("input", 16000, "out.wav")
    command = run.call_args.args[0]
    assert "-vn" in command
    assert float(command[command.index("-t") + 1]) == pytest.approx(1800 + 1 / 16000)


def test_throttled_progress_keeps_last_tick_and_cancel_checks(monkeypatch):
    from src import consumer
    monkeypatch.setattr(consumer.runtime.redis_client, "exists", MagicMock(return_value=0))
    channel = MagicMock()
    published = []
    channel.basic_publish.side_effect = lambda **kwargs: published.append(
        (kwargs["routing_key"], json.loads(kwargs["body"])))
    checks = consumer.runtime.redis_client.exists

    def process(msg, progress_callback):
        for segment in range(1, 101):
            progress_callback(15, "INFERENCE", {"current_segment": segment, "total_segments": 100})
        progress_callback(100, "POSTPROCESSING")
        return {"verdict": "REAL", "prob_fake": 0.1}

    monkeypatch.setattr(consumer.runtime, "process_task", process)
    args = (channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
            json.dumps({"analysis_id": "test"}).encode())
    consumer._handle_message(*args)
    progress = [body for route, body in published if route == "analysis.progress"]
    assert len(progress) == 3
    assert progress[-2]["details"]["current_segment"] == 100
    assert progress[-1]["progress"] == 100
    assert checks.call_count == 103

    published.clear()
    checks.reset_mock()
    checks.side_effect = [0, 0, 1]
    consumer._handle_message(*args)
    assert len(published) == 1
    assert published[0][0] == "analysis.progress"
    assert checks.call_count == 3


@pytest.mark.parametrize("duration", ["1800", "1", "N/A", None])
def test_container_boundary_and_unknown_duration(inference_module, monkeypatch, duration):
    run = MagicMock(return_value=SimpleNamespace(
        returncode=0, stdout=json.dumps({"format": {"duration": duration}}).encode()))
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    instance(inference_module)._check_duration("input")
    assert run.call_args.kwargs["timeout"] == 10


def test_configured_limit_applies_before_extraction(inference_module, monkeypatch):
    monkeypatch.setattr(inference_module, "MEDIA_MAX_DURATION_SECONDS", 60)
    run = MagicMock(return_value=SimpleNamespace(
        returncode=0, stdout=b'{"format":{"duration":"60.1"}}'))
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    with pytest.raises(inference_module.MediaTooLongError):
        instance(inference_module)._check_duration("input")


def test_waveform_boundary_uses_bounded_read(inference_module, monkeypatch):
    import numpy as np
    module = inference_module
    audio = MagicMock(frames=16000, samplerate=16000)
    audio.read.return_value = np.zeros((16000, 1), dtype="float32")
    monkeypatch.setattr(module, "MEDIA_MAX_DURATION_SECONDS", 1)
    monkeypatch.setattr(module, "sf", MagicMock())
    module.sf.SoundFile.return_value.__enter__.return_value = audio
    monkeypatch.setattr(module, "torch", MagicMock(from_numpy=lambda data: data))
    result = instance(module)._load_wav("input")
    assert len(result) == 16000
    audio.read.assert_called_once_with(frames=16001, dtype="float32", always_2d=True)
    audio.read.return_value = np.zeros((16001, 1), dtype="float32")
    with pytest.raises(module.MediaTooLongError):
        instance(module)._load_wav("input")


@pytest.mark.parametrize("failure", ["extract", "read", "cancel"])
def test_decoded_temp_cleanup_on_failure(inference_module, monkeypatch, tmp_path, failure):
    from detector_runtime import AnalysisCancelled
    module = inference_module
    analyzer = instance(module)
    analyzer._check_duration = MagicMock()
    wav = tmp_path / "input_16k.wav"

    def extract(*args):
        wav.write_bytes(b"partial")
        if failure == "extract":
            raise RuntimeError("failed")

    analyzer._extract_audio = extract
    analyzer._load_wav = MagicMock(side_effect=module.MediaTooLongError())

    def progress(pct, stage):
        if pct == 10 and failure == "cancel":
            raise AnalysisCancelled()

    with pytest.raises(AnalysisCancelled if failure == "cancel" else RuntimeError):
        analyzer.analyze(str(tmp_path / "input"), progress_callback=progress)
    assert not wav.exists()


@pytest.mark.parametrize("transport", [None, "publish", "ack"])
def test_overlong_input_publishes_confirmed_failure_before_ack(
        inference_module, monkeypatch, transport):
    import pika

    from src import consumer
    module = inference_module
    monkeypatch.setattr(module.subprocess, "run", MagicMock(return_value=SimpleNamespace(
        returncode=0, stdout=b'{"format":{"duration":"1801"}}')))
    monkeypatch.setattr(consumer, "audio_inference", instance(module))
    redis = MagicMock()
    redis.exists.return_value = 0
    monkeypatch.setattr(consumer.runtime, "redis_client", redis)
    s3 = MagicMock()
    monkeypatch.setattr(consumer.runtime, "s3_client", s3)
    channel = MagicMock()
    results = []

    def publish(**kwargs):
        if kwargs["routing_key"] == "analysis.results":
            channel.basic_ack.assert_not_called()
            assert kwargs["mandatory"]
            assert kwargs["properties"].delivery_mode == 2
            results.append(json.loads(kwargs["body"]))
            if transport == "publish":
                raise pika.exceptions.NackError([])

    channel.basic_publish.side_effect = publish
    if transport == "ack":
        channel.basic_ack.side_effect = pika.exceptions.StreamLostError("lost")
    task = {"analysis_id": "long", "file_bucket": "test", "file_key": "long.mp4"}
    args = (channel, MagicMock(delivery_tag=7), MagicMock(headers={}), json.dumps(task).encode())
    if transport:
        with pytest.raises(pika.exceptions.AMQPError):
            consumer._handle_message(*args)
    else:
        consumer._handle_message(*args)
    assert len(results) == 1
    assert results[0]["status"] == "FAILED"
    assert results[0]["result"] is None
    assert results[0]["error"] == {"code": "MEDIA_TOO_LONG",
                                   "message": "Materiał przekracza dopuszczalną długość. Wgraj krótsze nagranie."}
    s3.upload_file.assert_not_called()
    path = s3.download_file.call_args.args[2]
    from pathlib import Path
    assert not Path(path).parent.exists()
    if transport == "publish":
        channel.basic_ack.assert_not_called()
    else:
        channel.basic_ack.assert_called_once_with(delivery_tag=7)


def test_progress_emits_on_percentage_stage_or_time(monkeypatch):
    from detector_runtime import consumer as runtime_module

    from src import consumer
    clock = [0.0]
    monkeypatch.setattr(runtime_module.time, "monotonic", lambda: clock[0])
    redis = MagicMock()
    redis.exists.return_value = 0
    monkeypatch.setattr(consumer.runtime, "redis_client", redis)
    channel = MagicMock()
    published = []
    channel.basic_publish.side_effect = lambda **kwargs: published.append(
        (kwargs["routing_key"], json.loads(kwargs["body"])))

    def process(msg, progress_callback):
        progress_callback(15, "INFERENCE")
        progress_callback(15, "INFERENCE")
        clock[0] = .9
        progress_callback(15, "INFERENCE")
        clock[0] = 1
        progress_callback(15, "INFERENCE")
        progress_callback(16, "INFERENCE")
        progress_callback(16, "POSTPROCESSING")
        progress_callback(100, "POSTPROCESSING")
        return {"verdict": "REAL", "prob_fake": 0.1}

    monkeypatch.setattr(consumer.runtime, "process_task", process)
    consumer._handle_message(channel, MagicMock(delivery_tag=1), MagicMock(headers={}),
                             b'{"analysis_id":"progress"}')
    assert [(body["progress"], body["stage"]) for route, body in published
            if route == "analysis.progress"] == [
                (15, "INFERENCE"), (15, "INFERENCE"), (16, "INFERENCE"),
                (16, "POSTPROCESSING"), (100, "POSTPROCESSING")]
