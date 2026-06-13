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

import os
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort
import structlog
import torch
from prometheus_client import Gauge, Histogram

from .utils import (
    IMAGENET_MEAN,
    IMAGENET_STD,
    IMG_SIZE,
    NoFaceError,
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
from training.model import VideoLightningModule  # noqa: E402

MODEL_DIR = os.getenv("MODEL_DIR", os.path.join(PROJECT_ROOT, "training", "checkpoints", "effnet_lstm"))
BACKBONE_ONNX_PATH = os.path.join(MODEL_DIR, "backbone.onnx")
TEMPORAL_ONNX_PATH = os.path.join(MODEL_DIR, "temporal.onnx")
CKPT_PATH = os.path.join(MODEL_DIR, "last.ckpt")
MODEL_VERSION = os.getenv("MODEL_VERSION", "effnetb4-bilstm-v1.0.0")

# Prog decyzyjny z ewaluacji (training/eval.py); 0.5 = brak przesuniecia.
# Wkalibrowany w prob_fake, zeby Orchestrator (sztywne 0.5) mowil tym samym jezykiem.
DECISION_THRESHOLD = float(os.getenv("DECISION_THRESHOLD", "0.5"))
FRAME_STRATEGY = os.getenv("FRAME_STRATEGY", "uniform")  # uniform | middle2s (benchmark MO)
ONNX_INTRA_THREADS = int(os.getenv("ONNX_INTRA_THREADS", "4"))
DET_SIZE = int(os.getenv("DET_SIZE", "640"))      # rozdzielczosc detekcji RetinaFace (jak prep_cache)
MIN_FACES = int(os.getenv("MIN_FACES", "4"))      # ponizej -> NO_FACE_DETECTED (cache treningowy: 8/16)
GRADCAM_TOPK = int(os.getenv("GRADCAM_TOPK", "3"))
# Sciezka na pack buffalo_l; FaceAnalysis dociaga go przy pierwszym prepare(), jesli brak.
INSIGHTFACE_ROOT = os.getenv("INSIGHTFACE_ROOT", os.path.expanduser("~/.insightface"))

log = structlog.get_logger(__name__)

STAGE_DURATION = Histogram(
    "video_stage_duration_seconds",
    "Wall-clock time of one pipeline stage",
    ["stage"],  # face_detection | backbone | temporal | gradcam
    buckets=[0.1, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0],
)
MODEL_LOAD_TIME = Gauge(
    "video_model_load_duration_seconds",
    "Time taken to load the video models into memory",
)


class VideoInference:
    def __init__(self):
        start_load = time.time()
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

            app = FaceAnalysis(name="buffalo_l", root=INSIGHTFACE_ROOT,
                               allowed_modules=["detection"])
            app.prepare(ctx_id=0, det_size=(DET_SIZE, DET_SIZE))  # bez GPU spada na CPU EP
            self.face_app = app
        return self.face_app

    # ------------------------------------------------------------------ frames

    def _read_frames(self, video_path: str) -> tuple[list[tuple[float, np.ndarray]], float, float]:
        """-> ([(timestamp_s, frame_bgr)], duration_s, fps). Sekwencyjny grab()
        zamiast seekowania — szybsze i odporne na zepsute indeksy (jak prep_cache)."""
        cap = cv2.VideoCapture(video_path)
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        fps = cap.get(cv2.CAP_PROP_FPS) or 0.0
        if total <= 0:
            cap.release()
            raise VideoDecodeError(f"no decodable frames in {os.path.basename(video_path)}")
        wanted = set(sample_indices(total, fps, FRAME_STRATEGY).tolist())
        frames = []
        for i in range(total):
            if not cap.grab():
                break
            if i in wanted:
                ok, frame = cap.retrieve()
                if ok:
                    frames.append((i / fps if fps > 0 else 0.0, frame))
        cap.release()
        if not frames:
            raise VideoDecodeError(f"failed to decode sampled frames from {os.path.basename(video_path)}")
        duration = total / fps if fps > 0 else 0.0
        return frames, duration, fps

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

    def analyze(self, file_path: str, progress_callback=None, workdir: str = "/tmp") -> dict:
        def cb(pct, stage, details=None):
            if progress_callback:
                progress_callback(pct, stage, details)

        cb(10, "PREPROCESSING", {"step": "extract_frames"})
        frames, duration, fps = self._read_frames(file_path)

        with STAGE_DURATION.labels("face_detection").time():
            crops, timestamps = self._crop_faces(frames, progress_callback)
        faces_detected = len(crops)
        if faces_detected < MIN_FACES:
            raise NoFaceError(
                f"detected a face in only {faces_detected}/{len(frames)} sampled frames "
                f"(minimum {MIN_FACES})")
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
        top_idx = top_attention_frames(attention, GRADCAM_TOPK)

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
                "frames_sampled": len(crops),
                "faces_detected": faces_detected,
                "frame_predictions": [
                    {"timestamp": timestamps[i], "attention": round(float(attention[i]), 4)}
                    for i in range(len(crops))
                ],
            },
            "local_gradcam_paths": gradcam_paths,  # consumer uploaduje i zamienia na gradcam_keys
        }
