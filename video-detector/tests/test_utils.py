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
    ResolutionTooHighError,
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

    @pytest.mark.parametrize("logit", [1000.0, -1000.0, 1e308, -1e308])
    def test_extreme_logits_do_not_overflow(self, logit):
        prob, verdict, confidence = derive_outputs(logit, threshold=0.3)
        assert prob == (1.0 if logit > 0 else 0.0)
        assert verdict == ("FAKE" if logit > 0 else "REAL")
        assert confidence == 1.0

    @pytest.mark.parametrize("logit", [float("nan"), float("inf"), float("-inf")])
    def test_non_finite_logit_is_a_domain_error(self, logit):
        with pytest.raises(InferenceError) as error:
            derive_outputs(logit)
        assert error.value.code == "PROCESSING_ERROR"
        assert str(error.value) == "Nie udało się przetworzyć materiału. Spróbuj ponownie."

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

    def test_padded_frames_are_never_selected(self):
        # 5 real faces padded to SEQ_LEN by repeating the last crop.
        attn = np.full(SEQ_LEN, 0.01)
        attn[[1, 3]] = [0.2, 0.1]
        attn[5:] = 0.5
        assert top_attention_frames(attn, 3, real_frames=5) == [1, 3, 4]


class TestErrorCodes:
    @pytest.mark.parametrize("error_type, code, message", [
        (InferenceError, "PROCESSING_ERROR",
         "Nie udało się przetworzyć materiału. Spróbuj ponownie."),
        (VideoDecodeError, "VIDEO_DECODE_FAILED",
         "Nie udało się odczytać obrazu z pliku. Plik może być uszkodzony."),
        (NoFaceError, "NO_FACE_DETECTED",
         "Nie wykryto twarzy w nagraniu. Wgraj materiał, na którym widać twarz."),
        (ResolutionTooHighError, "MEDIA_RESOLUTION_TOO_HIGH",
         "Rozdzielczość nagrania jest za wysoka (maks. 4K)."),
    ])
    def test_codes_and_messages_match_amqp_contract(self, error_type, code, message):
        from detector_runtime import DetectorError

        error = error_type()
        assert isinstance(error, DetectorError)
        assert (error.code, str(error)) == (code, message)
