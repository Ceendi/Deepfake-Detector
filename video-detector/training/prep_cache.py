"""Jednorazowy preprocessing FF++ -> cache cropow twarzy (patrz datasets.py).

Na kazde video: uniform sampling 16 klatek -> RetinaFace (InsightFace,
buffalo_l, tylko modul detection) -> wybor najwiekszej twarzy -> 5-landmark
alignment (norm_crop) -> crop 224x224 -> JPEG q95. Wynik + manifest.csv
laduja w --out.

Kluczowe decyzje:
- Podzial train/val/test wg OFICJALNYCH splitow FF++ (train.json/val.json/
  test.json z repo FaceForensics). Fake '{a}_{b}.mp4' trafia do splitu tylko
  gdy OBA id sa w tym samym splicie — inaczej tozsamosc z train wycieka do
  val/test i metryki klamia (data leakage).
- Cache trzyma JPEG-i, nie Parquet jak audio: surowe piksele 224x224x3 sa
  ~40x wieksze od mel-spektrogramow, a augmentacje (ImageCompression!)
  musza dzialac na obrazach, nie na cechach CNN.
- Limity per split per klasa (domyslnie 1000/200/300 wg briefu); fake'i
  brane round-robin z metod (Deepfakes/Face2Face/FaceSwap), zeby zaden
  attack type nie dominowal.

Uzycie (host z GPU lub Colab):
    python prep_cache.py --ffpp-root <FF++ root> --splits-dir <splits json> \
        --out data/ffpp_cache
"""

import argparse
import json
import os
import sys
from itertools import zip_longest

import cv2
import numpy as np
import pandas as pd
from tqdm import tqdm

SEQ_LEN = 16
IMG_SIZE = 224
REAL_DIR = os.path.join("original_sequences", "youtube")
FAKE_METHODS = ["Deepfakes", "Face2Face", "FaceSwap"]


def load_official_splits(splits_dir: str) -> dict[str, set[str]]:
    """train/val/test.json: listy par ["071","054"] -> zbior id per split."""
    splits = {}
    for name in ("train", "val", "test"):
        path = os.path.join(splits_dir, f"{name}.json")
        with open(path, encoding="utf-8") as f:
            pairs = json.load(f)
        splits[name] = {vid for pair in pairs for vid in pair}
    return splits


def split_of(stem: str, splits: dict[str, set[str]]) -> str | None:
    """Original '000' -> split id. Fake '000_003' -> split tylko gdy oba id razem."""
    ids = stem.split("_")
    for name, members in splits.items():
        if all(i in members for i in ids):
            return name
    return None


def sample_indices(total: int, fps: float, strategy: str) -> np.ndarray:
    if strategy == "middle2s" and fps > 0:
        window = int(fps * 2)
        center = total // 2
        start = max(0, center - window // 2)
        end = min(total - 1, center + window // 2)
    else:
        start, end = 0, max(total - 1, 0)
    return np.unique(np.linspace(start, end, SEQ_LEN).round().astype(int))


def read_frames(video_path: str, strategy: str) -> list[np.ndarray]:
    cap = cv2.VideoCapture(video_path)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fps = cap.get(cv2.CAP_PROP_FPS)
    if total <= 0:
        cap.release()
        return []
    wanted = set(sample_indices(total, fps, strategy).tolist())
    frames = []
    # sekwencyjny grab() zamiast seekowania — szybsze i odporne na zepsute indeksy
    for i in range(total):
        if not cap.grab():
            break
        if i in wanted:
            ok, frame = cap.retrieve()
            if ok:
                frames.append(frame)
    cap.release()
    return frames


def crop_faces(frames: list[np.ndarray], face_app, min_faces: int) -> list[np.ndarray] | None:
    from insightface.utils.face_align import norm_crop

    crops = []
    for frame in frames:
        faces = face_app.get(frame)
        if not faces:
            continue
        face = max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))
        crops.append(norm_crop(frame, face.kps, image_size=IMG_SIZE))
    if len(crops) < min_faces:
        return None
    while len(crops) < SEQ_LEN:
        crops.append(crops[-1].copy())
    return crops[:SEQ_LEN]


def collect_videos(ffpp_root: str, compression: str, splits: dict[str, set[str]]):
    """-> lista (video_path, method, stem, label, split); method='real' dla originali.

    Obslugiwane uklady datasetu:
    - oficjalny (skrypt download z repo FaceForensics):
        original_sequences/youtube/<comp>/videos/*.mp4
        manipulated_sequences/<metoda>/<comp>/videos/*.mp4
    - splaszczony (dystrybucje z Kaggle, np. "FaceForensics++ C23"):
        original/*.mp4, Deepfakes/*.mp4, Face2Face/*.mp4, ...
      (kompresja jest wtedy zaszyta w samym datasecie — --compression ignorowane)
    """
    flat = os.path.isdir(os.path.join(ffpp_root, "original"))
    if flat:
        print("Wykryto splaszczony uklad datasetu (Kaggle) — --compression ignorowane")
        sources = [("original", "real", 0)] + [(m, m, 1) for m in FAKE_METHODS]
    else:
        sources = [(os.path.join(REAL_DIR, compression, "videos"), "real", 0)] + [
            (os.path.join("manipulated_sequences", m, compression, "videos"), m, 1)
            for m in FAKE_METHODS
        ]
    items = []
    for rel, method, label in sources:
        video_dir = os.path.join(ffpp_root, rel)
        if not os.path.isdir(video_dir):
            print(f"WARN: missing {video_dir}, skipping {method}")
            continue
        for name in sorted(os.listdir(video_dir)):
            if not name.endswith(".mp4"):
                continue
            stem = name[:-4]
            split = split_of(stem, splits)
            if split is None:
                continue
            items.append((os.path.join(video_dir, name), method, stem, label, split))
    return items


def apply_caps(items, caps: dict[str, int]):
    """Per (split, klasa): reale do limitu, fake'i round-robin z metod."""
    selected = []
    for split, cap in caps.items():
        reals = [it for it in items if it[4] == split and it[3] == 0]
        selected.extend(reals[:cap])

        by_method = [
            [it for it in items if it[4] == split and it[1] == m] for m in FAKE_METHODS
        ]
        fakes = [it for round_ in zip_longest(*by_method) for it in round_ if it is not None]
        selected.extend(fakes[:cap])
    return selected


def main():
    parser = argparse.ArgumentParser(description="FF++ face-crop cache builder")
    parser.add_argument("--ffpp-root", required=True, help="Korzen datasetu FaceForensics++")
    parser.add_argument("--splits-dir",
                        default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "splits"),
                        help="Katalog z oficjalnymi train.json/val.json/test.json "
                             "(domyslnie training/splits, commitowane w repo)")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                      "data", "ffpp_cache"))
    parser.add_argument("--compression", default="c23", choices=["raw", "c23", "c40"])
    parser.add_argument("--strategy", default="uniform", choices=["uniform", "middle2s"],
                        help="uniform: caly klip (train); middle2s: jak inferencja produkcyjna")
    parser.add_argument("--train-cap", type=int, default=1000, help="Limit per klasa w train")
    parser.add_argument("--val-cap", type=int, default=200)
    parser.add_argument("--test-cap", type=int, default=300)
    parser.add_argument("--min-faces", type=int, default=8,
                        help="Minimalna liczba klatek z twarza, ponizej — video pomijane")
    parser.add_argument("--det-size", type=int, default=640)
    args = parser.parse_args()

    from insightface.app import FaceAnalysis

    face_app = FaceAnalysis(name="buffalo_l", allowed_modules=["detection"])
    face_app.prepare(ctx_id=0, det_size=(args.det_size, args.det_size))

    splits = load_official_splits(args.splits_dir)
    items = collect_videos(args.ffpp_root, args.compression, splits)
    if not items:
        print("BLAD: nie znaleziono zadnych video — sprawdz --ffpp-root i --compression")
        sys.exit(1)
    items = apply_caps(items, {"train": args.train_cap, "val": args.val_cap, "test": args.test_cap})
    print(f"Wybrano {len(items)} video do przetworzenia")

    rows, skipped = [], 0
    for video_path, method, stem, label, split in tqdm(items, desc="Caching face crops"):
        clip_rel = os.path.join("crops", method, stem)
        clip_abs = os.path.join(args.out, clip_rel)

        if os.path.isdir(clip_abs) and len(os.listdir(clip_abs)) >= SEQ_LEN:
            rows.append((clip_rel, label, split, method, stem))  # idempotentnosc
            continue

        frames = read_frames(video_path, args.strategy)
        crops = crop_faces(frames, face_app, args.min_faces) if frames else None
        if crops is None:
            skipped += 1
            continue

        os.makedirs(clip_abs, exist_ok=True)
        for i, crop in enumerate(crops):
            cv2.imwrite(os.path.join(clip_abs, f"frame_{i:02d}.jpg"), crop,
                        [cv2.IMWRITE_JPEG_QUALITY, 95])
        rows.append((clip_rel, label, split, method, stem))

    manifest = pd.DataFrame(rows, columns=["clip_dir", "label", "split", "method", "video_id"])
    os.makedirs(args.out, exist_ok=True)
    manifest.to_csv(os.path.join(args.out, "manifest.csv"), index=False)

    print(f"\nZapisano manifest: {len(manifest)} klipow, pominieto {skipped} (brak twarzy/dekodowanie)")
    print(manifest.groupby(["split", "label"]).size().to_string())


if __name__ == "__main__":
    main()
