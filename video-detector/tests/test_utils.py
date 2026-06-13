"""Pure pipeline math (src/utils.py) — runs without torch/onnx/cv2.

Training parity matters here: sample_indices and normalize_clip must behave exactly
like training/prep_cache.py / training/datasets.py, otherwise production inference
feeds the model a distribution it has never seen.
"""
import math

import numpy as np
import pytest

from src.utils import (
    IMAGENET_MEAN,
    IMAGENET_STD,
    SEQ_LEN,
    InferenceError,
    NoFaceError,
    VideoDecodeError,
    calibration_shift,
    derive_outputs,
    normalize_clip,
    pad_crops,
    sample_indices,
    top_attention_frames,
)


class TestSampleIndices:
    def test_uniform_spans_whole_clip(self):
        idx = sample_indices(total=300, fps=30.0, strategy="uniform")
        assert idx[0] == 0 and idx[-1] == 299
        assert len(idx) == SEQ_LEN
        assert all(np.diff(idx) > 0)  # unique, sorted

    def test_short_clip_yields_fewer_unique_indices(self):
        # < SEQ_LEN frames: np.unique collapses duplicates; pad_crops fills the rest
        idx = sample_indices(total=5, fps=30.0, strategy="uniform")
        assert len(idx) == 5
        assert list(idx) == [0, 1, 2, 3, 4]

    def test_middle2s_window_centers_on_clip(self):
        idx = sample_indices(total=300, fps=30.0, strategy="middle2s")
        assert idx[0] >= 120 and idx[-1] <= 180  # 2s window around frame 150

    def test_single_frame_video(self):
        idx = sample_indices(total=1, fps=30.0, strategy="uniform")
        assert list(idx) == [0]


class TestPadCrops:
    def test_pads_by_repeating_last_crop(self):
        crops = [np.full((4, 4, 3), i, dtype=np.uint8) for i in range(3)]
        padded, ts = pad_crops(crops, [0.0, 0.5, 1.0])
        assert len(padded) == SEQ_LEN and len(ts) == SEQ_LEN
        assert (padded[-1] == 2).all()
        assert ts[-1] == 1.0
        padded[-1][0, 0, 0] = 99  # .copy() — padding must not alias the source crop
        assert padded[2][0, 0, 0] == 2

    def test_truncates_overlong_sequences(self):
        crops = [np.zeros((2, 2, 3), dtype=np.uint8)] * (SEQ_LEN + 4)
        padded, ts = pad_crops(crops, [float(i) for i in range(SEQ_LEN + 4)])
        assert len(padded) == SEQ_LEN and ts[-1] == float(SEQ_LEN - 1)


class TestNormalizeClip:
    def test_matches_training_normalization(self):
        # white pixel: (1.0 - mean) / std, exactly like FFPPClipDataset.__getitem__
        crops = [np.full((8, 8, 3), 255, dtype=np.uint8)] * SEQ_LEN
        clip = normalize_clip(crops)
        assert clip.shape == (SEQ_LEN, 3, 8, 8)
        assert clip.dtype == np.float32
        expected = (1.0 - IMAGENET_MEAN) / IMAGENET_STD
        np.testing.assert_allclose(clip[0, :, 0, 0], expected, rtol=1e-5)


class TestDeriveOutputs:
    def test_logit_zero_is_the_decision_boundary(self):
        prob, verdict, confidence = derive_outputs(0.0, threshold=0.5)
        assert prob == 0.5 and verdict == "REAL" and confidence == 0.0

    def test_high_logit_is_confident_fake(self):
        prob, verdict, confidence = derive_outputs(3.0, threshold=0.5)
        assert verdict == "FAKE"
        assert prob == pytest.approx(1 / (1 + math.exp(-3.0)), abs=1e-4)
        assert confidence == pytest.approx(abs(prob - 0.5) * 2, abs=1e-4)

    def test_calibrated_threshold_becomes_the_new_boundary(self):
        # eval picked tau=0.3: a raw prob_fake of 0.3 must map to published 0.5
        raw_logit = math.log(0.3 / 0.7)
        prob, verdict, _ = derive_outputs(raw_logit, threshold=0.3)
        assert prob == 0.5 and verdict == "REAL"
        prob_above, verdict_above, _ = derive_outputs(raw_logit + 0.1, threshold=0.3)
        assert prob_above > 0.5 and verdict_above == "FAKE"

    def test_invalid_threshold_rejected(self):
        with pytest.raises(ValueError):
            calibration_shift(0.0)
        with pytest.raises(ValueError):
            calibration_shift(1.0)


class TestTopAttentionFrames:
    def test_returns_indices_sorted_by_weight_desc(self):
        attn = np.array([0.05, 0.30, 0.10, 0.55])
        assert top_attention_frames(attn, 3) == [3, 1, 2]

    def test_k_larger_than_sequence_is_safe(self):
        assert len(top_attention_frames(np.array([0.5, 0.5]), 5)) == 2


class TestErrorCodes:
    def test_codes_match_amqp_contract(self):
        assert InferenceError("x").code == "PROCESSING_ERROR"
        assert VideoDecodeError("x").code == "VIDEO_DECODE_FAILED"
        assert NoFaceError("x").code == "NO_FACE_DETECTED"
        assert InferenceError("x", code="CUSTOM").code == "CUSTOM"
