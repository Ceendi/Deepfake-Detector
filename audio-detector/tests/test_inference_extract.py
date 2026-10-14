"""_extract_audio failure surfacing.

Heavy ML deps (torch/onnx/matplotlib/...) are stubbed in sys.modules so the real
src.inference imports in the light test env; only the ffmpeg wrapper is exercised.
"""
import json
import shutil
import subprocess
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest


def _instance(inference_module):
    # Skip __init__ (loads model checkpoints) — only the ffmpeg wrapper is under test.
    return inference_module.AudioInference.__new__(inference_module.AudioInference)


def probe(streams):
    return SimpleNamespace(returncode=0, stdout=json.dumps({"streams": streams}).encode())


def test_ffmpeg_failure_has_neutral_message(inference_module, monkeypatch):
    run = MagicMock(side_effect=[probe([{"index": 1}]), SimpleNamespace(
        returncode=1, stderr=b"/tmp/private/input.mp4: Invalid data")])
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    with pytest.raises(RuntimeError) as error:
        _instance(inference_module)._extract_audio("/tmp/private/input.mp4", 16000, "out.wav")
    assert str(error.value) == "Nie udało się wyodrębnić ścieżki audio z materiału."
    assert getattr(error.value, "code", "PROCESSING_ERROR") == "PROCESSING_ERROR"


def test_ffmpeg_success_is_silent(inference_module, monkeypatch):
    run = MagicMock(side_effect=[probe([{"index": 1}]), SimpleNamespace(returncode=0)])
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    _instance(inference_module)._extract_audio("in.bin", 16000, "out.wav")
    assert run.call_args.args[0][0] == "ffmpeg"


def test_no_audio_track_is_identified_before_extraction(inference_module, monkeypatch):
    run = MagicMock(return_value=probe([]))
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    with pytest.raises(RuntimeError) as error:
        _instance(inference_module)._extract_audio("/tmp/private/input.mp4", 16000, "out.wav")
    assert error.value.code == "NO_AUDIO_TRACK"
    assert str(error.value) == "Materiał nie zawiera ścieżki audio."
    assert run.call_count == 1
    command = run.call_args.args[0]
    assert command[command.index("-select_streams") + 1] == "a"


@pytest.mark.parametrize("outcome", [
    SimpleNamespace(returncode=1, stdout=b""),
    SimpleNamespace(returncode=0, stdout=b"invalid"),
    SimpleNamespace(returncode=0, stdout=b"{}"),
    SimpleNamespace(returncode=0, stdout=b'{"streams":null}'),
    subprocess.TimeoutExpired("ffprobe /tmp/private/input.mp4", 10),
    FileNotFoundError("/usr/private/ffprobe"),
])
def test_probe_failure_is_not_accepted_as_missing_audio(inference_module, monkeypatch, outcome):
    run = MagicMock(side_effect=outcome) if isinstance(outcome, Exception) else MagicMock(return_value=outcome)
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    with pytest.raises(RuntimeError) as error:
        _instance(inference_module)._extract_audio("/tmp/private/input.mp4", 16000, "out.wav")
    assert getattr(error.value, "code", "PROCESSING_ERROR") == "PROCESSING_ERROR"
    assert "/" not in str(error.value)
    assert run.call_count == 1


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"), reason="ffmpeg required")
@pytest.mark.parametrize("audio", [False, True])
def test_real_video_stream_probe(inference_module, tmp_path, audio):
    path = tmp_path / "input.mp4"
    command = ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=size=16x16:rate=10"]
    if audio:
        command += ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=16000", "-c:a", "aac"]
    subprocess.run(command + ["-t", "0.2", "-c:v", "mpeg4", str(path)], check=True)
    analyzer = _instance(inference_module)
    output = tmp_path / "out.wav"
    if audio:
        analyzer._extract_audio(str(path), 16000, str(output))
        assert output.stat().st_size > 0
    else:
        with pytest.raises(RuntimeError) as error:
            analyzer._extract_audio(str(path), 16000, str(output))
        assert error.value.code == "NO_AUDIO_TRACK"
        assert not output.exists()


@pytest.mark.parametrize("transport", [None, "publish", "ack"])
def test_missing_track_publishes_wire_fixture_before_ack(inference_module, monkeypatch, transport):
    from pathlib import Path

    import pika

    from src import consumer
    analyzer = _instance(inference_module)
    analyzer._check_duration = MagicMock()
    analyzer._load_wav = MagicMock()
    analyzer.generate_gradcam = MagicMock()
    monkeypatch.setattr(inference_module.subprocess, "run", MagicMock(return_value=probe([])))
    monkeypatch.setattr(consumer, "audio_inference", analyzer)
    monkeypatch.setattr(consumer.runtime, "redis_client", MagicMock(exists=MagicMock(return_value=0)))
    storage = MagicMock()
    monkeypatch.setattr(consumer.runtime, "s3_client", storage)
    fixture = json.loads((Path(__file__).parents[2] / "orchestrator/src/test/resources/contracts/audio-no-track-result.json").read_text())
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
    task = {"analysis_id": fixture["analysis_id"], "correlation_id": fixture["correlation_id"],
            "file_bucket": "test", "file_key": "input.mp4"}
    args = (channel, MagicMock(delivery_tag=7), MagicMock(headers={}), json.dumps(task).encode())
    if transport:
        with pytest.raises(pika.exceptions.AMQPError):
            consumer._handle_message(*args)
    else:
        consumer._handle_message(*args)
    assert results == [fixture]
    analyzer._load_wav.assert_not_called()
    analyzer.generate_gradcam.assert_not_called()
    storage.upload_file.assert_not_called()
    assert not Path(storage.download_file.call_args.args[2]).parent.exists()
    if transport == "publish":
        channel.basic_ack.assert_not_called()
    else:
        channel.basic_ack.assert_called_once_with(delivery_tag=7)


def test_ffmpeg_runs_bounded_without_stdin(inference_module, monkeypatch):
    run = MagicMock(side_effect=[probe([{"index": 1}]), SimpleNamespace(returncode=0)])
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    _instance(inference_module)._extract_audio("in.bin", 16000, "out.wav")
    command, options = run.call_args.args[0], run.call_args.kwargs
    assert "-nostdin" in command
    assert options["stdin"] == subprocess.DEVNULL
    assert options["timeout"] == 60 + inference_module.MEDIA_MAX_DURATION_SECONDS / 10


def test_hung_ffmpeg_becomes_neutral_processing_error(inference_module, monkeypatch):
    run = MagicMock(side_effect=[probe([{"index": 1}]),
                                 subprocess.TimeoutExpired("ffmpeg -i /tmp/private/in.mp4", 240)])
    monkeypatch.setattr(inference_module.subprocess, "run", run)
    with pytest.raises(RuntimeError) as error:
        _instance(inference_module)._extract_audio("/tmp/private/in.mp4", 16000, "out.wav")
    assert str(error.value) == "Nie udało się wyodrębnić ścieżki audio z materiału."
    assert not isinstance(error.value, inference_module.DetectorError)
