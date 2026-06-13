"""Czyste funkcje pipeline'u video + klasy bledow — celowo bez ML deps (numpy only),
zeby testy kontraktowe biegaly bez torcha/onnx i zeby consumer.py mogl importowac
kody bledow bez ladowania calego stacku inferencji.

Stale preprocessing MUSZA byc 1:1 z treningiem (training/datasets.py,
training/prep_cache.py) — model widzial crop 224x224, RGB, ImageNet mean/std.
"""

import math

import numpy as np

SEQ_LEN = 16
IMG_SIZE = 224
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


class InferenceError(RuntimeError):
    """Blad pipeline'u z kodem publikowanym w `error.code` (analysis.results)."""

    code = "PROCESSING_ERROR"

    def __init__(self, message: str, code: str | None = None):
        super().__init__(message)
        if code is not None:
            self.code = code


class VideoDecodeError(InferenceError):
    code = "VIDEO_DECODE_FAILED"


class NoFaceError(InferenceError):
    code = "NO_FACE_DETECTED"


def sample_indices(total: int, fps: float, strategy: str = "uniform") -> np.ndarray:
    """Indeksy klatek do probkowania — kopia semantyki training/prep_cache.py.

    uniform: SEQ_LEN klatek rownomiernie z calego klipu (tak byl budowany cache
    treningowy — inferencja musi probkowac z tej samej dystrybucji);
    middle2s: okno 2 s wokol srodka (wariant z briefu, do benchmarku MO).
    np.unique moze zwrocic < SEQ_LEN indeksow przy krotkich klipach — padding
    do SEQ_LEN robi crop_faces/pad_crops, identycznie jak w cache'u.
    """
    if strategy == "middle2s" and fps > 0:
        window = int(fps * 2)
        center = total // 2
        start = max(0, center - window // 2)
        end = min(total - 1, center + window // 2)
    else:
        start, end = 0, max(total - 1, 0)
    return np.unique(np.linspace(start, end, SEQ_LEN).round().astype(int))


def pad_crops(crops: list, timestamps: list[float]) -> tuple[list, list[float]]:
    """Klatki bez twarzy sa pomijane — dopelnij sekwencje powielajac ostatni crop
    (training/prep_cache.py robi to samo budujac cache)."""
    crops = list(crops[:SEQ_LEN])
    timestamps = list(timestamps[:SEQ_LEN])
    while len(crops) < SEQ_LEN:
        crops.append(crops[-1].copy())
        timestamps.append(timestamps[-1])
    return crops, timestamps


def normalize_clip(crops_rgb: list[np.ndarray]) -> np.ndarray:
    """list[(H, W, 3) uint8 RGB] -> (T, 3, H, W) float32 jak w FFPPClipDataset."""
    clip = np.stack(crops_rgb).astype(np.float32) / 255.0
    clip = (clip - IMAGENET_MEAN) / IMAGENET_STD
    return np.ascontiguousarray(clip.transpose(0, 3, 1, 2))


def calibration_shift(threshold: float) -> float:
    """Przesuniecie logitu wkalibrowujace prog decyzyjny w wyjscie modelu.

    Caly system (Orchestrator agreguje przy progu 0.5) zaklada granice 0.5;
    jesli ewaluacja wskazala optymalny prog tau != 0.5, przesuwamy logit o
    -logit(tau), zeby sigmoid(logit') = 0.5 wypadal dokladnie na tau.
    """
    if not 0.0 < threshold < 1.0:
        raise ValueError(f"threshold must be in (0, 1), got {threshold}")
    return math.log(threshold / (1.0 - threshold))


def derive_outputs(logit: float, threshold: float = 0.5) -> tuple[float, str, float]:
    """logit -> (prob_fake, verdict, confidence).

    Model zwraca wylacznie logit; prob_fake to sigmoid po kalibracji progu,
    verdict to porownanie z 0.5, a confidence = |prob - 0.5| * 2 jest swiadomie
    ta sama konwencja UI co w audio i Orchestratorze (NIE skalibrowana
    niepewnosc) — patrz docs/contracts/amqp-messages.md.
    """
    shifted = logit - calibration_shift(threshold)
    prob = 1.0 / (1.0 + math.exp(-shifted))
    verdict = "FAKE" if prob > 0.5 else "REAL"
    confidence = abs(prob - 0.5) * 2.0
    return round(prob, 4), verdict, round(confidence, 4)


def top_attention_frames(attention: np.ndarray, k: int) -> list[int]:
    """Indeksy top-K klatek wg wag atencji (malejaco) — wybor klatek pod Grad-CAM."""
    order = np.argsort(np.asarray(attention))[::-1]
    return [int(i) for i in order[:k]]
