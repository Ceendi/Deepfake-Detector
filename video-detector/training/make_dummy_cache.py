"""Syntetyczny cache do smoke testu treningu — bez dostepu do FF++.

Generuje losowe "cropy twarzy" (szum + gradient, zeby JPEG mial co kompresowac)
w ukladzie identycznym jak prep_cache.py. Model niczego sie na tym nie nauczy —
chodzi wylacznie o weryfikacje, ze caly loop (dataset -> augmentacje -> forward
-> backward -> checkpoint) dziala na danej maszynie.

    python make_dummy_cache.py --out data/smoke_cache --clips-per-split 8
"""

import argparse
import os

import cv2
import numpy as np
import pandas as pd

SEQ_LEN = 16
IMG_SIZE = 224


def make_clip(rng: np.random.Generator) -> list[np.ndarray]:
    base = rng.integers(0, 255, (IMG_SIZE, IMG_SIZE, 3), dtype=np.uint8)
    gradient = np.linspace(0, 80, IMG_SIZE, dtype=np.uint8)[None, :, None]
    frames = []
    for t in range(SEQ_LEN):
        noise = rng.integers(-15, 15, base.shape, dtype=np.int16)
        frame = np.clip(base.astype(np.int16) + noise + gradient + t, 0, 255).astype(np.uint8)
        frames.append(frame)
    return frames


def main():
    parser = argparse.ArgumentParser(description="Dummy cache generator (smoke test)")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                      "data", "smoke_cache"))
    parser.add_argument("--clips-per-split", type=int, default=8, help="Per klasa, per split")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = np.random.default_rng(args.seed)
    rows = []
    for split in ("train", "val", "test"):
        for label, method in ((0, "real"), (1, "Deepfakes")):
            for i in range(args.clips_per_split):
                stem = f"{split}_{method}_{i:03d}"
                clip_rel = os.path.join("crops", method, stem)
                clip_abs = os.path.join(args.out, clip_rel)
                os.makedirs(clip_abs, exist_ok=True)
                for t, frame in enumerate(make_clip(rng)):
                    cv2.imwrite(os.path.join(clip_abs, f"frame_{t:02d}.jpg"), frame,
                                [cv2.IMWRITE_JPEG_QUALITY, 95])
                rows.append((clip_rel, label, split, method, stem))

    manifest = pd.DataFrame(rows, columns=["clip_dir", "label", "split", "method", "video_id"])
    manifest.to_csv(os.path.join(args.out, "manifest.csv"), index=False)
    print(f"Dummy cache: {len(manifest)} klipow w {args.out}")
    print(manifest.groupby(["split", "label"]).size().to_string())


if __name__ == "__main__":
    main()
