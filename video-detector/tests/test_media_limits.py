"""Exercise production frame guards and confirmed failures without ML checkpoints."""
import importlib.util
import json
from pathlib import Path
from unittest.mock import MagicMock

import numpy as np
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


def capture(module, monkeypatch, total, fps, actual=None, width=1280, height=720, frame=None):
    cap = MagicMock()
    props = {module.cv2.CAP_PROP_FRAME_COUNT: total, module.cv2.CAP_PROP_FPS: fps,
             module.cv2.CAP_PROP_FRAME_WIDTH: width, module.cv2.CAP_PROP_FRAME_HEIGHT: height}
    cap.get.side_effect = props.__getitem__
    count = 0

    def grab():
        nonlocal count
        count += 1
        return count <= (total if actual is None else actual)

    cap.grab.side_effect = grab
    cap.retrieve.return_value = (True, np.zeros((height, width, 3), np.uint8)
                                 if frame is None else frame)
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
    assert cap.grab.call_count == module.FALLBACK_FPS + 1
    cap.release.assert_called_once()


def test_missing_fps_accepts_ordinary_clip_within_limit(module, monkeypatch):
    # 61 s at 30 fps without FPS metadata: well under 1800 s, so it must not be rejected.
    cap = capture(module, monkeypatch, total=1, fps=0, actual=61 * 30)
    frames, duration, fps = analyzer(module)._read_frames("input")
    assert frames and (duration, fps) == (0, 0)
    assert cap.grab.call_count == 61 * 30 + 1
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


@pytest.mark.parametrize("width, height", [(3840, 2161), (2161, 3840), (16000, 16000)])
def test_resolution_above_limit_rejected_before_decoding(module, monkeypatch, width, height):
    cap = capture(module, monkeypatch, total=10, fps=25, width=width, height=height)
    with pytest.raises(RuntimeError) as error:
        analyzer(module)._read_frames("/private/input.mov")
    assert error.value.code == "MEDIA_RESOLUTION_TOO_HIGH"
    assert str(error.value) == "Rozdzielczość nagrania jest za wysoka (maks. 4K)."
    cap.grab.assert_not_called()
    cap.release.assert_called_once()


@pytest.mark.parametrize("width, height", [(3840, 2160), (2160, 3840)])
def test_4k_in_either_orientation_is_accepted(module, monkeypatch, width, height):
    capture(module, monkeypatch, total=2, fps=2, width=width, height=height)
    module.cv2.resize.side_effect = lambda frame, size, interpolation: np.zeros(
        (size[1], size[0], 3), np.uint8)
    frames, _, _ = analyzer(module)._read_frames("input")
    assert [frame.shape[:2] for _, frame in frames] == (
        [(1080, 1920)] * 2 if width > height else [(1920, 1080)] * 2)


def test_understated_resolution_metadata_is_caught_on_decoded_frame(module, monkeypatch):
    cap = capture(module, monkeypatch, total=4, fps=2, width=0, height=0,
                  frame=np.zeros((4000, 4000, 3), np.uint8))
    with pytest.raises(RuntimeError) as error:
        analyzer(module)._read_frames("input")
    assert error.value.code == "MEDIA_RESOLUTION_TOO_HIGH"
    assert cap.retrieve.call_count == 1
    cap.release.assert_called_once()


def test_retained_frames_are_downscaled_to_1920(module, monkeypatch):
    capture(module, monkeypatch, total=2, fps=2, width=2560, height=1440)
    module.cv2.resize.side_effect = lambda frame, size, interpolation: np.zeros(
        (size[1], size[0], 3), np.uint8)
    frames, _, _ = analyzer(module)._read_frames("input")
    assert [frame.shape for _, frame in frames] == [(1080, 1920, 3)] * 2
    assert module.cv2.resize.call_args.args[1] == (1920, 1080)
    assert module.cv2.resize.call_args.kwargs["interpolation"] == module.cv2.INTER_AREA


def test_small_frames_are_not_resized(module, monkeypatch):
    capture(module, monkeypatch, total=2, fps=2, width=1920, height=1080)
    analyzer(module)._read_frames("input")
    module.cv2.resize.assert_not_called()


@pytest.mark.parametrize("value", ["0", "-1", "invalid", "1.5"])
def test_invalid_pixel_limit_is_rejected(load_inference, monkeypatch, value):
    monkeypatch.setenv("MEDIA_MAX_PIXELS", value)
    with pytest.raises(ValueError):
        load_inference()


def test_default_pixel_limit_is_4k(load_inference, monkeypatch):
    monkeypatch.delenv("MEDIA_MAX_PIXELS", raising=False)
    assert load_inference().MEDIA_MAX_PIXELS == 3840 * 2160


def test_inflated_fps_metadata_caps_frame_budget(module, monkeypatch):
    monkeypatch.setattr(module, "MEDIA_MAX_DURATION_SECONDS", 1, raising=False)
    cap = capture(module, monkeypatch, total=1, fps=90_000, actual=10_000)
    with pytest.raises(RuntimeError) as error:
        analyzer(module)._read_frames("input")
    assert error.value.code == "MEDIA_TOO_LONG"
    assert cap.grab.call_count == module.MAX_BUDGET_FPS + 1


class FakeClock:
    def __init__(self, step):
        self.now, self.step = 0.0, step

    def monotonic(self):
        self.now += self.step
        return self.now


def test_long_decoding_reports_periodic_progress(module, monkeypatch):
    # One simulated second per grab: 60 frames span about a minute of decoding.
    monkeypatch.setattr(module, "time", FakeClock(step=1.0))
    capture(module, monkeypatch, total=60, fps=1)
    ticks = []
    analyzer(module)._read_frames("input", lambda *args: ticks.append(args))
    assert 10 <= len(ticks) <= 12
    assert {stage for _, stage, _ in ticks} == {"PREPROCESSING"}
    percents = [pct for pct, _, _ in ticks]
    assert percents == sorted(percents) and 10 <= percents[0] and percents[-1] <= 14
    assert [details["step"] for _, _, details in ticks] == ["extract_frames"] * len(ticks)


def test_fast_decoding_does_not_spam_progress(module, monkeypatch):
    monkeypatch.setattr(module, "time", FakeClock(step=0.001))
    capture(module, monkeypatch, total=1000, fps=30)
    callback = MagicMock()
    analyzer(module)._read_frames("input", callback)
    callback.assert_not_called()


def test_cancel_during_decoding_stops_reading(module, monkeypatch):
    from detector_runtime import AnalysisCancelled

    monkeypatch.setattr(module, "time", FakeClock(step=1.0))
    cap = capture(module, monkeypatch, total=1000, fps=30)

    def cancelled(*args):
        raise AnalysisCancelled()

    with pytest.raises(AnalysisCancelled):
        analyzer(module)._read_frames("input", cancelled)
    assert cap.grab.call_count < 10
    cap.release.assert_called_once()


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


def test_consumer_publishes_resolution_failure_before_ack(module, monkeypatch):
    from src import consumer

    capture(module, monkeypatch, total=10, fps=25, width=16000, height=16000)
    monkeypatch.setattr(consumer, "video_inference", analyzer(module))
    redis = MagicMock()
    redis.exists.return_value = 0
    monkeypatch.setattr(consumer.runtime, "redis_client", redis)
    monkeypatch.setattr(consumer.runtime, "s3_client", MagicMock())
    channel = MagicMock()
    events = []
    channel.basic_publish.side_effect = lambda **kwargs: events.append(
        (kwargs["routing_key"], json.loads(kwargs["body"])))
    channel.basic_ack.side_effect = lambda **kwargs: events.append(("ack", None))
    consumer._handle_message(channel, MagicMock(delivery_tag=7), MagicMock(headers={}),
                             json.dumps({"analysis_id": "huge", "file_bucket": "deepfake-uploads",
                                         "file_key": "input.avi"}).encode())
    results = [payload for key, payload in events if key == "analysis.results"]
    assert results[0]["status"] == "FAILED"
    assert results[0]["error"] == {"code": "MEDIA_RESOLUTION_TOO_HIGH",
                                   "message": "Rozdzielczość nagrania jest za wysoka (maks. 4K)."}
    assert events[-1] == ("ack", None)


def test_analyze_forwards_progress_and_limits_gradcam_to_real_faces(module, monkeypatch):
    model = analyzer(module)
    callback = MagicMock()
    frames = [(float(t), np.zeros((4, 4, 3), np.uint8)) for t in range(16)]
    model._read_frames = MagicMock(return_value=(frames, 16.0, 1.0))
    model._crop_faces = MagicMock(return_value=([np.zeros((4, 4, 3), np.uint8)] * 5,
                                                [float(t) for t in range(5)]))
    model.backbone_session = MagicMock()
    model.backbone_session.run.return_value = [np.zeros((16, 8), np.float32)]
    model.temporal_session = MagicMock()
    model.temporal_session.run.return_value = [np.array([[0.5]], np.float32)]
    model.net = MagicMock()
    model.net.temporal.lstm.return_value = (MagicMock(), None)
    model.net.temporal.pool.return_value = (MagicMock(), MagicMock())
    model._gradcam_frames = MagicMock(return_value=[])
    monkeypatch.setattr(module, "normalize_clip", lambda crops: np.zeros((16, 3, 4, 4)))
    top = MagicMock(return_value=[0])
    monkeypatch.setattr(module, "top_attention_frames", top)
    model.analyze("input", progress_callback=callback)
    model._read_frames.assert_called_once_with("input", callback)
    assert top.call_args.args[2] == 5
