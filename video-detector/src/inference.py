"""Inferencja produkcyjna video: ONNX (verdict) + checkpoint PyTorch (atencja + Grad-CAM).

Pipeline na jedno zadanie (1:1 z preprocessingiem treningu — training/prep_cache.py):

  1. uniform sampling SEQ_LEN=16 klatek z calego klipu (cv2, sekwencyjny grab),
  2. RetinaFace (insightface buffalo_l, detection-only) -> najwieksza twarz
     -> 5-landmark alignment (norm_crop) -> crop 224x224 -> BGR->RGB,
  3. normalizacja ImageNet -> (16, 3, 224, 224),
  4. backbone.onnx (16,3,224,224)->(16,1792), temporal.onnx -> logit;
     kalibracja progu (logit shift) -> prob_fake / verdict / confidence,
  5. replay czesci temporalnej w torchu na cechach z ONNX -> wagi atencji
     (frame_predictions) + gradient d(logit)/d(feats) — seed do kroku 6,
  6. HiResCAM tylko dla top-K klatek wg atencji: forward+backward backbone'u
     per klatka z grad_outputs=dlogit/dfeat[t] (chain rule — wynik identyczny
     z pelnym backwardem przez caly graf, ale liczymy K=3 klatki zamiast 16)
     -> overlay JET na cropie -> PNG do /tmp (upload robi consumer).

HiResCAM (grad (x) act), nie Grad-CAM++ — uzasadnienie i test okluzji w
training/README.md (sekcja Grad-CAM): B4 ma narozne kanaly-"biasy", ktore
CAM++ rozswietla niezaleznie od wejscia. Silnik jest adaptacja
training/gradcam.py bez zaleznosci od datasets.py (albumentations/pandas
nie wchodza do obrazu produkcyjnego).
"""

import math
import os
import sys
import tempfile
import time

import cv2
import numpy as np
import onnxruntime as ort
import structlog
import torch
from detector_runtime import MediaTooLongError
from detector_runtime.logging import model_output
from detector_runtime.metrics import inference_duration_buckets
from detector_runtime.model_files import ensure_model_files
from prometheus_client import Gauge, Histogram

from .config import load_config
from .utils import (
    IMAGENET_MEAN,
    IMAGENET_STD,
    IMG_SIZE,
    NoFaceError,
    ResolutionTooHighError,
    VideoDecodeError,
    derive_outputs,
    normalize_clip,
    pad_crops,
    sample_indices,
    top_attention_frames,
)

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
sys.path.append(PROJECT_ROOT)
from training.model import VideoLightningModule

MODELS_MANIFEST = os.path.join(PROJECT_ROOT, "models.toml")
MODEL_DIR = os.getenv("MODEL_DIR", os.path.join(PROJECT_ROOT, "training", "checkpoints", "effnet_lstm"))
BACKBONE_ONNX_PATH = os.path.join(MODEL_DIR, "backbone.onnx")
TEMPORAL_ONNX_PATH = os.path.join(MODEL_DIR, "temporal.onnx")
CKPT_PATH = os.path.join(MODEL_DIR, "last.ckpt")
MODEL_VERSION = os.getenv("MODEL_VERSION", "effnetb4-bilstm-v1.0.0")
SETTINGS = load_config()
MEDIA_MAX_DURATION_SECONDS = SETTINGS["MEDIA_MAX_DURATION_SECONDS"]
# Without usable FPS metadata, assume a high frame rate: the frame budget still bounds decoding
# without rejecting ordinary 24-60 fps clips that fit the duration limit.
FALLBACK_FPS = 60.0
# Inflated FPS metadata (e.g. 90000) must not inflate the decoding budget.
MAX_BUDGET_FPS = 240.0
# Orientation-independent width*height limit (default 3840x2160).
MEDIA_MAX_PIXELS = SETTINGS["MEDIA_MAX_PIXELS"]
# Sampled frames are held until face detection; bound their memory (DET_SIZE is far smaller).
MAX_FRAME_SIDE = 1920
# Decoding long or high-resolution clips can exceed the stuck-job idle budget without ticks.
DECODE_PROGRESS_INTERVAL_SECONDS = 5.0

DECISION_THRESHOLD = SETTINGS["DECISION_THRESHOLD"]
FRAME_STRATEGY = SETTINGS["FRAME_STRATEGY"]
ONNX_INTRA_THREADS = SETTINGS["ONNX_INTRA_THREADS"]
DET_SIZE = SETTINGS["DET_SIZE"]
MIN_FACES = SETTINGS["MIN_FACES"]
GRADCAM_TOPK = SETTINGS["GRADCAM_TOPK"]
# Sciezka na pack buffalo_l; FaceAnalysis dociaga go przy pierwszym prepare(), jesli brak.
INSIGHTFACE_ROOT = os.getenv("INSIGHTFACE_ROOT", os.path.expanduser("~/.insightface"))

log = structlog.get_logger(__name__)

STAGE_DURATION = Histogram(
    "video_stage_duration_seconds",
    "Wall-clock time of one pipeline stage",
    ["stage"],  # face_detection | backbone | temporal | gradcam
    buckets=inference_duration_buckets(),
)
MODEL_LOAD_TIME = Gauge(
    "video_model_load_duration_seconds",
    "Time taken to load the video models into memory",
)


def _check_resolution(width: float, height: float) -> None:
    pixels = width * height
    # Missing (0) or non-finite metadata falls through to the per-frame check.
    if math.isfinite(pixels) and pixels > MEDIA_MAX_PIXELS:
        log.warning("media_resolution_too_high", width=width, height=height,
                    max_pixels=MEDIA_MAX_PIXELS)
        raise ResolutionTooHighError()


def _bounded_frame(frame: np.ndarray) -> np.ndarray:
    height, width = frame.shape[:2]
    # Container metadata can understate the decoded size.
    _check_resolution(width, height)
    longest = max(width, height)
    if longest <= MAX_FRAME_SIDE:
        return frame
    scale = MAX_FRAME_SIDE / longest
    size = (max(1, round(width * scale)), max(1, round(height * scale)))
    return cv2.resize(frame, size, interpolation=cv2.INTER_AREA)


class VideoInference:
    def __init__(self):
        ensure_model_files(MODELS_MANIFEST, MODEL_DIR)
        start_load = time.time()
        torch.set_num_threads(ONNX_INTRA_THREADS)
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = ONNX_INTRA_THREADS
        self.backbone_session = ort.InferenceSession(
            BACKBONE_ONNX_PATH, sess_options=opts, providers=["CPUExecutionProvider"])
        self.temporal_session = ort.InferenceSession(
            TEMPORAL_ONNX_PATH, sess_options=opts, providers=["CPUExecutionProvider"])
        # Checkpoint trzymamy obok ONNX wylacznie do atencji + Grad-CAM (ONNX nie ma
        # grafu gradientow). pretrained=False: wagi i tak nadpisze checkpoint.
        module = VideoLightningModule.load_from_checkpoint(
            CKPT_PATH, map_location=torch.device("cpu"),
            pretrained=False, grad_checkpointing=False,
        )
        module.eval()
        self.net = module.model
        self.face_app = None  # lazy: prepare() dociaga pack z sieci przy pierwszym uzyciu
        MODEL_LOAD_TIME.set(time.time() - start_load)

    def _ensure_face_app(self):
        if self.face_app is None:
            from insightface.app import FaceAnalysis

            opts = ort.SessionOptions()
            opts.intra_op_num_threads = ONNX_INTRA_THREADS
            with model_output():
                app = FaceAnalysis(name="buffalo_l", root=INSIGHTFACE_ROOT,
                                   allowed_modules=["detection"], sess_options=opts,
                                   providers=["CPUExecutionProvider"])
                app.prepare(ctx_id=-1, det_size=(DET_SIZE, DET_SIZE))
            self.face_app = app
        return self.face_app

    # ------------------------------------------------------------------ frames

    def _read_frames(self, video_path: str, progress_callback=None
                     ) -> tuple[list[tuple[float, np.ndarray]], float, float]:
        """-> ([(timestamp_s, frame_bgr)], duration_s, fps). Sekwencyjny grab()
        zamiast seekowania — szybsze i odporne na zepsute indeksy (jak prep_cache)."""
        cap = cv2.VideoCapture(video_path)
        try:
            reported = cap.get(cv2.CAP_PROP_FRAME_COUNT)
            total = int(reported) if math.isfinite(reported) and reported > 0 else 0
            fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
            fps = fps if math.isfinite(fps) and fps > 0 else 0.0
            width = cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0.0
            height = cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0.0
            duration = total / fps if fps > 0 else 0.0
            if math.isfinite(duration) and duration > MEDIA_MAX_DURATION_SECONDS:
                raise MediaTooLongError()
            _check_resolution(width, height)
            # Only the guard uses the fallback; result timestamps keep fps 0.
            budget_fps = min(fps or FALLBACK_FPS, MAX_BUDGET_FPS)
            wanted = set(sample_indices(total, fps, FRAME_STRATEGY).tolist()) if total else set()
            frames = []
            i = 0
            last_tick = time.monotonic()
            # Read to EOF: an understated frame count must not bypass the duration guard.
            while cap.grab():
                if (i + 1) / budget_fps > MEDIA_MAX_DURATION_SECONDS:
                    raise MediaTooLongError()
                ok, frame = cap.retrieve()
                if not ok or frame is None:
                    raise VideoDecodeError()
                _check_resolution(frame.shape[1], frame.shape[0])
                if i in wanted:
                    frames.append((i / fps if fps > 0 else 0.0, _bounded_frame(frame)))
                i += 1
                now = time.monotonic()
                if progress_callback and now - last_tick >= DECODE_PROGRESS_INTERVAL_SECONDS:
                    # The runtime callback also raises AnalysisCancelled for cancelled analyses.
                    progress_callback(10 + min(4, 5 * i // max(total, i, 1)), "PREPROCESSING",
                                      {"step": "extract_frames", "frame": i})
                    last_tick = now
        finally:
            cap.release()
        if i and i != total:
            # Reopen rather than seek: corrupt container indexes must not choose the samples.
            wanted = set(sample_indices(i, fps, FRAME_STRATEGY).tolist())
            frames = []
            cap = cv2.VideoCapture(video_path)
            try:
                for index in range(i):
                    if not cap.grab():
                        raise VideoDecodeError()
                    if index in wanted:
                        ok, frame = cap.retrieve()
                        if not ok or frame is None:
                            raise VideoDecodeError()
                        frames.append((index / fps if fps > 0 else 0.0, _bounded_frame(frame)))
                    now = time.monotonic()
                    if progress_callback and now - last_tick >= DECODE_PROGRESS_INTERVAL_SECONDS:
                        progress_callback(14, "PREPROCESSING", {"step": "extract_frames"})
                        last_tick = now
            finally:
                cap.release()
        if not frames:
            log.warning("video_decode_failed", reason="no_sampled_frames", frames_read=i)
            raise VideoDecodeError()
        return frames, i / fps if fps > 0 else 0.0, fps

    def _crop_faces(self, frames, progress_callback=None):
        """Najwieksza twarz per klatka -> norm_crop 224 BGR. Klatki bez twarzy pomijane."""
        from insightface.utils.face_align import norm_crop

        face_app = self._ensure_face_app()
        crops, timestamps = [], []
        total = len(frames)
        for k, (ts, frame) in enumerate(frames):
            faces = face_app.get(frame)
            if faces:
                face = max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))
                crops.append(norm_crop(frame, face.kps, image_size=IMG_SIZE))
                timestamps.append(round(ts, 2))
            # 15 -> 45% rozlozone po klatkach; co czwarta + ostatnia, zeby nie spamowac brokera
            if progress_callback and (k % 4 == 3 or k == total - 1):
                progress_callback(15 + int(30 * (k + 1) / total), "PREPROCESSING",
                                  {"step": "face_detection", "frame": k + 1, "total_frames": total})
        return crops, timestamps

    # ----------------------------------------------------------------- gradcam

    def _gradcam_frames(self, clip: np.ndarray, feats_grad: torch.Tensor,
                        top_idx: list[int], out_dir: str) -> list[tuple[str, int]]:
        """HiResCAM per wybrana klatka przez vector-Jacobian product.

        d(logit)/d(act_t) = d(logit)/d(feat_t) . d(feat_t)/d(act_t) — backward
        backbone'u per klatka z seedem feats_grad[t] daje dokladnie ten sam
        gradient co backward przez caly graf, bez liczenia pozostalych klatek.
        """
        acts_box: dict[str, torch.Tensor] = {}
        grads_box: dict[str, torch.Tensor] = {}
        # bn2 = ostatnie aktywowane mapy przestrzenne B4 (7x7x1792) — jak training/gradcam.py
        h1 = self.net.backbone.bn2.register_forward_hook(
            lambda m, args, out: acts_box.__setitem__("v", out.detach()))
        h2 = self.net.backbone.bn2.register_full_backward_hook(
            lambda m, gin, gout: grads_box.__setitem__("v", gout[0].detach()))
        paths = []
        try:
            for t in top_idx:
                frame = torch.from_numpy(clip[t]).unsqueeze(0)            # (1, 3, 224, 224)
                self.net.backbone.zero_grad(set_to_none=True)
                feat = self.net.backbone(frame)                           # (1, 1792)
                feat.backward(gradient=feats_grad[t].unsqueeze(0))
                cam = torch.relu((grads_box["v"] * acts_box["v"]).sum(dim=1))[0]  # (7, 7)
                cam = torch.nn.functional.interpolate(
                    cam[None, None], size=(IMG_SIZE, IMG_SIZE),
                    mode="bilinear", align_corners=False)[0, 0]
                cam = cam - cam.min()
                cam = (cam / (cam.max() + 1e-8)).numpy()

                # denormalizacja cropu do overlay'a (te same proporcje co training/gradcam.py)
                rgb = clip[t].transpose(1, 2, 0) * IMAGENET_STD + IMAGENET_MEAN
                bgr = cv2.cvtColor(np.clip(rgb * 255.0, 0, 255).astype(np.uint8),
                                   cv2.COLOR_RGB2BGR)
                heat = cv2.applyColorMap((cam * 255).astype(np.uint8), cv2.COLORMAP_JET)
                overlay = cv2.addWeighted(bgr, 0.55, heat, 0.45, 0)
                path = os.path.join(out_dir, f"gradcam_frame_{t:02d}.png")
                cv2.imwrite(path, overlay)
                paths.append((path, t))
        finally:
            h1.remove()
            h2.remove()
        return paths

    # ----------------------------------------------------------------- analyze

    def analyze(self, file_path: str, progress_callback=None, workdir: str | None = None) -> dict:
        def cb(pct, stage, details=None):
            if progress_callback:
                progress_callback(pct, stage, details)

        # The consumer passes the per-task download directory; ad-hoc calls use the system default.
        workdir = workdir or tempfile.gettempdir()
        cb(10, "PREPROCESSING", {"step": "extract_frames"})
        frames, duration, fps = self._read_frames(file_path, progress_callback)

        with STAGE_DURATION.labels("face_detection").time():
            crops, timestamps = self._crop_faces(frames, progress_callback)
        faces_detected = len(crops)
        if faces_detected < MIN_FACES:
            log.warning("no_face_detected", faces_detected=faces_detected,
                        frames_sampled=len(frames), min_faces=MIN_FACES)
            raise NoFaceError()
        crops, timestamps = pad_crops(crops, timestamps)

        clip = normalize_clip([cv2.cvtColor(c, cv2.COLOR_BGR2RGB) for c in crops])

        cb(55, "INFERENCE", {"step": "backbone"})
        with STAGE_DURATION.labels("backbone").time():
            feats = self.backbone_session.run(None, {"frames": clip})[0]  # (16, 1792)

        cb(70, "INFERENCE", {"step": "temporal"})
        with STAGE_DURATION.labels("temporal").time():
            logit = float(self.temporal_session.run(
                None, {"features": feats[None, ...]})[0].reshape(-1)[0])
            # Replay czesci temporalnej w torchu na TYCH SAMYCH cechach: wagi atencji
            # (frame_predictions) + d(logit)/d(feats) jako seed VJP dla Grad-CAM.
            # Czesc temporalna to ~2M parametrow — narzut pomijalny vs backbone.
            feats_t = torch.from_numpy(feats).unsqueeze(0).requires_grad_(True)
            seq, _ = self.net.temporal.lstm(feats_t)
            pooled, attention = self.net.temporal.pool(seq)
            torch_logit = self.net.temporal.head(pooled)
            torch_logit.backward()
            feats_grad = feats_t.grad[0]                                  # (16, 1792)
            attention = attention.detach()[0].numpy()                     # (16,)

        prob_fake, verdict, confidence = derive_outputs(logit, DECISION_THRESHOLD)
        top_idx = top_attention_frames(attention, GRADCAM_TOPK, faces_detected)

        cb(85, "POSTPROCESSING", {"step": "gradcam"})
        gradcam_paths: list[tuple[str, int]] = []
        try:
            with STAGE_DURATION.labels("gradcam").time():
                gradcam_paths = self._gradcam_frames(clip, feats_grad, top_idx, workdir)
        except Exception:
            # Heatmapy sa pomocnicze — ich brak nie moze polozyc wyniku analizy
            log.exception("gradcam_generation_failed")

        cb(100, "POSTPROCESSING", {"step": "finished"})
        return {
            "prob_fake": prob_fake,
            "verdict": verdict,
            "confidence": confidence,
            "model_version": MODEL_VERSION,
            # wagi atencji != prob_fake klatki: to wzgledny wklad klatki w decyzje
            # (suma = 1), patrz docs/contracts/amqp-messages.md
            "metadata": {
                "duration_seconds": round(duration, 2),
                "fps": round(fps, 2),
                "frames_sampled": len(frames),
                "faces_detected": faces_detected,
                "frame_predictions": [
                    {"timestamp": timestamps[i], "attention": round(float(attention[i]), 4)}
                    for i in range(faces_detected)
                ],
            },
            "local_gradcam_paths": gradcam_paths,  # consumer uploaduje i zamienia na gradcam_keys
        }
