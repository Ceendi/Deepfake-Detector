import os
import torch
import pandas as pd
import torchaudio
from torch.utils.data import Dataset, DataLoader
import lightning.pytorch as pl
from audiomentations import Compose, AddGaussianNoise, PitchShift, TimeStretch, RoomSimulator
def get_audio_augmentations():
    return Compose([
        AddGaussianNoise(p=0.5),
        PitchShift(p=0.5),
        TimeStretch(p=0.5),
        RoomSimulator(p=0.3),
    ])
class ASVspoofDataset(Dataset):
    def __init__(self, protocols_file, data_dir, transform=None, is_train=True, target_sr=16000):
        self.data_dir = data_dir
        self.transform = transform
        self.is_train = is_train
        self.target_sr = target_sr
        self.samples = self._load_samples(protocols_file)
    def _load_samples(self, protocols_file):
        df = pd.read_csv(
            protocols_file,
            sep=r"\s+",
            header=None,
            names=["speaker_id", "file_id", "env_id", "attack_id", "label"]
        )
        df["target"] = (df["label"] == "spoof").astype(int)
        return df[["file_id", "target"]].values.tolist()
    def __len__(self):
        return len(self.samples)
    def __getitem__(self, idx):
        file_id, target = self.samples[idx]
        file_path = os.path.join(self.data_dir, f"{file_id}.flac")
        waveform, sr = torchaudio.load(file_path)
        if waveform.shape[0] > 1:
            waveform = torch.mean(waveform, dim=0, keepdim=True)
        if sr != self.target_sr:
            resampler = torchaudio.transforms.Resample(orig_freq=sr, new_freq=self.target_sr)
            waveform = resampler(waveform)
        signal = waveform.squeeze(0)
        num_samples_needed = self.target_sr
        current_samples = signal.shape[0]
        if current_samples > num_samples_needed:
            if self.is_train:
                start_idx = torch.randint(0, current_samples - num_samples_needed, (1,)).item()
            else:
                start_idx = (current_samples - num_samples_needed) // 2
            signal = signal[start_idx:start_idx + num_samples_needed]
        elif current_samples < num_samples_needed:
            pad_length = num_samples_needed - current_samples
            signal = torch.nn.functional.pad(signal, (0, pad_length))
        if self.transform and self.is_train:
            signal_np = signal.numpy()
            augmented_np = self.transform(samples=signal_np, sample_rate=self.target_sr)
            signal = torch.from_numpy(augmented_np)
            current_samples = signal.shape[0]
            if current_samples > num_samples_needed:
                start_idx = torch.randint(0, current_samples - num_samples_needed, (1,)).item()
                signal = signal[start_idx:start_idx + num_samples_needed]
            elif current_samples < num_samples_needed:
                pad_length = num_samples_needed - current_samples
                signal = torch.nn.functional.pad(signal, (0, pad_length))
        return signal, torch.tensor(target, dtype=torch.float32)
class ASVspoofDataModule(pl.LightningDataModule):
    def __init__(self, data_root, batch_size=16, num_workers=4, target_sr=16000):
        super().__init__()
        self.data_root = data_root
        self.batch_size = batch_size
        self.num_workers = num_workers
        self.target_sr = target_sr
        self.augmentations = get_audio_augmentations()
    def setup(self, stage=None):
        if stage == "fit" or stage is None:
            self.train_dataset = ASVspoofDataset(
                protocols_file=os.path.join(self.data_root, "ASVspoof2019_LA_cm_protocols"
                                            , "ASVspoof2019.LA.cm.train.trn.txt"),
                data_dir=os.path.join(self.data_root, "ASVspoof2019_LA_train", "flac"),
                transform=self.augmentations,
                is_train=True,
                target_sr=self.target_sr,
            )
            self.val_dataset = ASVspoofDataset(
                protocols_file=os.path.join(self.data_root, "ASVspoof2019_LA_cm_protocols"
                                            , "ASVspoof2019.LA.cm.dev.trl.txt"),
                data_dir=os.path.join(self.data_root, "ASVspoof2019_LA_dev", "flac"),
                is_train=False,
                target_sr=self.target_sr,
            )
        if stage == "test" or stage is None:
            self.test_dataset = ASVspoofDataset(
                protocols_file=os.path.join(self.data_root, "ASVspoof2019_LA_cm_protocols",
                                            "ASVspoof2019.LA.cm.eval.trl.txt"),
                data_dir=os.path.join(self.data_root, "ASVspoof2019_LA_eval", "flac"),
                is_train=False,
                target_sr=self.target_sr
            )
    def train_dataloader(self):
        return DataLoader(self.train_dataset, batch_size=self.batch_size, shuffle=True, num_workers=self.num_workers)
    def val_dataloader(self):
        return DataLoader(self.val_dataset, batch_size=self.batch_size, shuffle=False, num_workers=self.num_workers)
    def test_dataloader(self):
        return DataLoader(self.test_dataset, batch_size=self.batch_size, shuffle=False, num_workers=self.num_workers)
class ParquetASVspoofDataset(Dataset):
    def __init__(self, parquet_path):
        self.df = pd.read_parquet(parquet_path)
    def __len__(self):
        return len(self.df)
    def __getitem__(self, idx):
        row = self.df.iloc[idx]
        features = torch.tensor(row['features'], dtype=torch.float32)
        features = features.view(1, 128, -1)
        label = torch.tensor(row['label'], dtype=torch.float32)
        return features, label
class ParquetASVspoofDataModule(pl.LightningDataModule):
    def __init__(self, cache_dir, batch_size=16, num_workers=4):
        super().__init__()
        self.cache_dir = cache_dir
        self.batch_size = batch_size
        self.num_workers = num_workers
    def setup(self, stage=None):
        if stage == "fit" or stage is None:
            self.train_dataset = ParquetASVspoofDataset(os.path.join(self.cache_dir, "melspec_train.parquet"))
            self.val_dataset = ParquetASVspoofDataset(os.path.join(self.cache_dir, "melspec_dev.parquet"))
        if stage == "test" or stage is None:
            self.test_dataset = ParquetASVspoofDataset(os.path.join(self.cache_dir, "melspec_eval.parquet"))
    def train_dataloader(self):
        return DataLoader(self.train_dataset, batch_size=self.batch_size, shuffle=True, num_workers=self.num_workers)
    def val_dataloader(self):
        return DataLoader(self.val_dataset, batch_size=self.batch_size, shuffle=False, num_workers=self.num_workers)
    def test_dataloader(self):
        return DataLoader(self.test_dataset, batch_size=self.batch_size, shuffle=False, num_workers=self.num_workers)
