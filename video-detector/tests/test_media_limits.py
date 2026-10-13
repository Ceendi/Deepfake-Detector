"""Exercise production frame guards and confirmed failures without ML checkpoints."""
import importlib.util
import json
from pathlib import Path
from unittest.mock import MagicMock

import pytest


@pytest.fixture
def load_inference(monkeypatch):
    import sys

    for name in ("cv2", "torch", "onnxruntime", "training", "training.model",
                 "prometheus_client"):
        monkeypatch.setitem(sys.modules, name, MagicMock())

    def load():
        spec = importlib.util.spec_from_file_location(
            "src._media_limits_inference", Path(__file__).parents[1] / "src/inference.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    return load


@pytest.fixture
def module(load_inference, monkeypatch):
    monkeypatch.setenv("MEDIA_MAX_DURATION_SECONDS", "1800")
    return load_inference()


def capture(module, monkeypatch, total, fps, actual=None):
    cap = MagicMock()
    cap.get.side_effect = [total, fps]
    count = 0

    def grab():
        nonlocal count
        count += 1
        return count <= (total if actual is None else actual)

    cap.grab.side_effect = grab
    cap.retrieve.return_value = (True, object())
    monkeypatch.setattr(module.cv2, "VideoCapture", MagicMock(return_value=cap))
    return cap


def analyzer(module):
    return module.VideoInference.__new__(module.VideoInference)


def test_long_metadata_rejected_before_grab_or_inference(module, monkeypatch):
    cap = capture(module, monkeypatch, total=1800 * 25 + 1, fps=25)
    model = analyzer(module)
    model._crop_faces = MagicMock()
    with pytest.raises(RuntimeError) as error:
        model.analyze("/private/input.mp4")
    assert getattr(error.value, "code", None) == "MEDIA_TOO_LONG"
    assert "/private" not in str(error.value)
    cap.grab.assert_not_called()
    cap.release.assert_called_once()
    model._crop_faces.assert_not_called()


def test_underreported_frame_count_cannot_bypass_limit(module, monkeypatch):
    monkeypatch.setattr(module, "MEDIA_MAX_DURATION_SECONDS", 1, raising=False)
    cap = capture(module, monkeypatch, total=2, fps=2, actual=10)
    with pytest.raises(RuntimeError) as error:
        analyzer(module)._read_frames("input")
    assert getattr(error.value, "code", None) == "MEDIA_TOO_LONG"
    assert cap.grab.call_count <= 4
    cap.release.assert_called_once()


@pytest.mark.parametrize("fps", [0, -1, float("nan"), float("inf")])
def test_missing_or_invalid_fps_has_bounded_frame_budget(module, monkeypatch, fps):
    monkeypatch.setattr(module, "MEDIA_MAX_DURATION_SECONDS", 1, raising=False)
    cap = capture(module, monkeypatch, total=1, fps=fps, actual=100)
    with pytest.raises(RuntimeError) as error:
        analyzer(module)._read_frames("input")
    assert getattr(error.value, "code", None) == "MEDIA_TOO_LONG"
    assert cap.grab.call_count <= 3
    cap.release.assert_called_once()


def test_exact_duration_boundary_keeps_sampled_frames(module, monkeypatch):
    monkeypatch.setattr(module, "MEDIA_MAX_DURATION_SECONDS", 1, raising=False)
    cap = capture(module, monkeypatch, total=2, fps=2)
    frames, duration, fps = analyzer(module)._read_frames("input")
    assert [timestamp for timestamp, _ in frames] == [0, .5]
    assert (duration, fps) == (1, 2)
    cap.release.assert_called_once()


def test_capture_is_released_on_decode_error(module, monkeypatch):
    cap = capture(module, monkeypatch, total=2, fps=2)
    cap.grab.side_effect = RuntimeError("decoder failed")
    with pytest.raises(RuntimeError, match="decoder failed"):
        analyzer(module)._read_frames("input")
    cap.release.assert_called_once()


@pytest.mark.parametrize("value", ["0", "-1", "nan", "inf", "-inf", "invalid"])
def test_invalid_duration_limit_is_rejected(load_inference, monkeypatch, value):
    monkeypatch.setenv("MEDIA_MAX_DURATION_SECONDS", value)
    with pytest.raises(ValueError):
        load_inference()


def test_default_duration_limit(load_inference, monkeypatch):
    monkeypatch.delenv("MEDIA_MAX_DURATION_SECONDS", raising=False)
    assert load_inference().MEDIA_MAX_DURATION_SECONDS == 1800


@pytest.mark.parametrize("transport", [None, "publish", "ack"])
def test_consumer_confirms_media_too_long_before_ack(module, monkeypatch, transport):
    import pika

    from src import consumer

    cap = capture(module, monkeypatch, total=1801, fps=1)
    monkeypatch.setattr(consumer, "video_inference", analyzer(module))
    redis = MagicMock()
    redis.exists.return_value = 0
    monkeypatch.setattr(consumer.runtime, "redis_client", redis)
    s3 = MagicMock()
    monkeypatch.setattr(consumer.runtime, "s3_client", s3)
    channel = MagicMock()
    events = []
    results = []

    def publish(**kwargs):
        if kwargs["routing_key"] == "analysis.results":
            events.append("confirmed_result")
            results.append(json.loads(kwargs["body"]))
            assert kwargs["mandatory"] is True
            assert kwargs["properties"].delivery_mode == 2
            if transport == "publish":
                raise pika.exceptions.AMQPConnectionError("confirm lost")

    def ack(**kwargs):
        events.append("ack")
        if transport == "ack":
            raise pika.exceptions.AMQPConnectionError("ack lost")

    channel.basic_publish.side_effect = publish
    channel.basic_ack.side_effect = ack
    args = (channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
            json.dumps({"analysis_id": "too-long", "file_bucket": "deepfake-uploads",
                        "file_key": "input.mp4"}).encode())
    if transport:
        with pytest.raises(pika.exceptions.AMQPConnectionError):
            consumer._handle_message(*args)
    else:
        consumer._handle_message(*args)
    assert len(results) == 1
    assert results[0]["status"] == "FAILED"
    assert results[0]["source"] == "video"
    assert results[0]["result"] is None
    assert results[0]["error"] == {
        "code": "MEDIA_TOO_LONG",
        "message": "Materiał przekracza dopuszczalną długość. Wgraj krótsze nagranie.",
    }
    assert events == (["confirmed_result"] if transport == "publish"
                      else ["confirmed_result", "ack"])
    s3.upload_file.assert_not_called()
    assert not Path(s3.download_file.call_args.args[2]).parent.exists()
    cap.grab.assert_not_called()
    cap.release.assert_called_once()
