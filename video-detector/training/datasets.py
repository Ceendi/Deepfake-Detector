"""Dataset FF++ na cache'owanych cropach twarzy (patrz prep_cache.py).

Cache (jednorazowo zbudowany przez prep_cache.py, bo dekodowanie video +
RetinaFace per epoke jest o rzedy wielkosci za wolne):

    <cache_dir>/
      manifest.csv                # clip_dir, label, split, method, video_id
      crops/<method>/<stem>/frame_00.jpg ... frame_15.jpg   (224x224, aligned)

Augmentacje (albumentations 2.x) sa nakladane przez ReplayCompose — te same
parametry transformacji na WSZYSTKICH klatkach klipu. Bez tego Bi-LSTM
widzialby sztuczne "migotanie" miedzy klatkami (np. flip co druga klatke),
ktorego nie ma ani w realnych, ani w fake'owych nagraniach.

ImageCompression(quality 40-90) jest krytyczny dla generalizacji
cross-dataset (Celeb-DF) — fake'i w internecie sa rekompresowane i model
nie moze polegac na artefaktach kompresji specyficznych dla FF++ c23.
"""

import os

import albumentations as A
import cv2
import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader, Dataset
import lightning.pytorch as pl

SEQ_LEN = 16
IMG_SIZE = 224
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def get_video_augmentations() -> A.ReplayCompose:
    return A.ReplayCompose(
        [
            A.HorizontalFlip(p=0.5),
            A.RandomBrightnessContrast(p=0.3),
            A.ImageCompression(quality_range=(40, 90), p=0.5),
            A.GaussNoise(p=0.3),
            A.CoarseDropout(
                num_holes_range=(1, 4),
                hole_height_range=(0.05, 0.15),
                hole_width_range=(0.05, 0.15),
                p=0.2,
            ),
        ]
    )


class FFPPClipDataset(Dataset):
    """Jeden sample = klip: tensor (T, 3, 224, 224) + label (0=real, 1=fake)."""

    def __init__(self, manifest: pd.DataFrame, cache_dir: str, is_train: bool = False):
        self.manifest = manifest.reset_index(drop=True)
        self.cache_dir = cache_dir
        self.is_train = is_train
        self.augment = get_video_augmentations() if is_train else None

    def __len__(self) -> int:
        return len(self.manifest)

    def _load_frames(self, clip_dir: str) -> list[np.ndarray]:
        full_dir = os.path.join(self.cache_dir, clip_dir)
        names = sorted(f for f in os.listdir(full_dir) if f.endswith(".jpg"))
        frames = []
        for name in names[:SEQ_LEN]:
            img = cv2.imread(os.path.join(full_dir, name))
            if img is None:
                continue
            frames.append(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        if not frames:
            raise FileNotFoundError(f"No readable frames in {full_dir}")
        while len(frames) < SEQ_LEN:  # krotkie klipy: powiel ostatnia klatke
            frames.append(frames[-1].copy())
        return frames

    def __getitem__(self, idx):
        row = self.manifest.iloc[idx]
        frames = self._load_frames(row["clip_dir"])

        if self.augment is not None:
            first = self.augment(image=frames[0])
            replay = first["replay"]
            frames = [first["image"]] + [
                A.ReplayCompose.replay(replay, image=f)["image"] for f in frames[1:]
            ]

        clip = np.stack(frames).astype(np.float32) / 255.0     # (T, H, W, 3)
        clip = (clip - IMAGENET_MEAN) / IMAGENET_STD
        clip = torch.from_numpy(clip).permute(0, 3, 1, 2)       # (T, 3, H, W)
        label = torch.tensor(float(row["label"]), dtype=torch.float32)
        return clip, label


class FFPPDataModule(pl.LightningDataModule):
    """Sygnatura (cache_dir, batch_size, num_workers) — uzywana przez
    train.py, eval.py i grid_search.py (setup("test") dla ewaluacji)."""

    def __init__(self, cache_dir: str, batch_size: int = 8, num_workers: int = 4):
        super().__init__()
        self.cache_dir = cache_dir
        self.batch_size = batch_size
        self.num_workers = num_workers

    def _split(self, name: str) -> pd.DataFrame:
        manifest = pd.read_csv(os.path.join(self.cache_dir, "manifest.csv"))
        part = manifest[manifest["split"] == name]
        if part.empty:
            raise ValueError(f"Split '{name}' is empty — rebuild cache with prep_cache.py")
        return part

    def setup(self, stage=None):
        if stage == "fit" or stage is None:
            self.train_dataset = FFPPClipDataset(self._split("train"), self.cache_dir, is_train=True)
            self.val_dataset = FFPPClipDataset(self._split("val"), self.cache_dir, is_train=False)
        if stage == "test" or stage is None:
            self.test_dataset = FFPPClipDataset(self._split("test"), self.cache_dir, is_train=False)

    def train_dataloader(self):
        return DataLoader(
            self.train_dataset, batch_size=self.batch_size, shuffle=True,
            num_workers=self.num_workers, pin_memory=True, persistent_workers=self.num_workers > 0,
        )

    def val_dataloader(self):
        return DataLoader(
            self.val_dataset, batch_size=self.batch_size, shuffle=False,
            num_workers=self.num_workers, pin_memory=True, persistent_workers=self.num_workers > 0,
        )

    def test_dataloader(self):
        return DataLoader(
            self.test_dataset, batch_size=self.batch_size, shuffle=False,
            num_workers=self.num_workers, pin_memory=True,
        )
