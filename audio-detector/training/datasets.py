import os
import glob
import random
import torch
import pandas as pd
import torchaudio
import soundfile as sf
from torch.utils.data import Dataset, DataLoader
import lightning.pytorch as pl
from audiomentations import (
    Compose,
    AddGaussianNoise,
    PitchShift,
    TimeStretch,
    RoomSimulator,
    Mp3Compression,
    LowPassFilter,
    HighPassFilter,
    Gain,
    ClippingDistortion,
)

def get_audio_augmentations():
    """Augmentacje symulujące warunki 'in-the-wild': kompresja kodekowa,
    zmiana głośności, filtry pasmowe, pogłos, szum i zniekształcenia.
    Kluczowe dla generalizacji poza sterylne nagrania laboratoryjne ASVspoof."""
    return Compose([
        # --- Augmentacje kodekowe (NOWE - krytyczne dla YouTube/streaming) ---
        Mp3Compression(min_bitrate=32, max_bitrate=192, p=0.4),
        Gain(min_gain_db=-12, max_gain_db=6, p=0.5),
        LowPassFilter(min_cutoff_freq=2000, max_cutoff_freq=7500, p=0.3),
        HighPassFilter(min_cutoff_freq=20, max_cutoff_freq=500, p=0.2),
        ClippingDistortion(max_percentile_threshold=10, p=0.1),
        # --- Augmentacje oryginalne ---
        AddGaussianNoise(min_amplitude=0.001, max_amplitude=0.015, p=0.5),
        PitchShift(min_semitones=-2, max_semitones=2, p=0.3),
        TimeStretch(min_rate=0.9, max_rate=1.1, p=0.3),
        RoomSimulator(p=0.3),
    ])


class ASVspoofDataset(Dataset):
    def __init__(
        self,
        protocols_file,
        data_dir,
        extra_fake_dir=None,
        in_the_wild_dir=None,
        transform=None,
        is_train=True,
        target_sr=16000,
        fake_to_real_ratio=1.0,
    ):
        """
        Args:
            in_the_wild_dir: Ścieżka do folderu In-the-Wild (zawierającego meta.csv + pliki .wav).
                Dodaje prawdziwe i fake nagrania z YouTube/mediów do zbioru.
            fake_to_real_ratio: Maksymalny stosunek FAKE:REAL w zbiorze.
                1.0 = równa liczba (1:1), 2.0 = 2x więcej FAKE niż REAL.
                Używane TYLKO dla is_train=True. Wartość <= 0 wyłącza downsampling.
        """
        self.data_dir = data_dir
        self.transform = transform
        self.is_train = is_train
        self.target_sr = target_sr
        self.fake_to_real_ratio = fake_to_real_ratio
        self.in_the_wild_dir = in_the_wild_dir
        self.samples = self._load_samples(protocols_file, data_dir, extra_fake_dir)

    def _load_samples(self, protocols_file, data_dir, extra_fake_dir):
        df = pd.read_csv(
            protocols_file,
            sep=r"\s+",
            header=None,
            names=["speaker_id", "file_id", "env_id", "attack_id", "label"]
        )
        df["target"] = (df["label"] == "spoof").astype(int)

        real_samples = []
        fake_samples = []
        for _, row in df.iterrows():
            path = os.path.join(data_dir, f"{row['file_id']}.flac")
            if row["target"] == 0:
                real_samples.append((path, 0))
            else:
                fake_samples.append((path, 1))

        if self.is_train and extra_fake_dir and os.path.exists(extra_fake_dir):
            fake_files = glob.glob(os.path.join(extra_fake_dir, "**", "*.wav"), recursive=True) + \
                         glob.glob(os.path.join(extra_fake_dir, "**", "*.flac"), recursive=True)
            for f in fake_files:
                fake_samples.append((f, 1))

        # --- In-the-Wild dataset (YouTube/media recordings) ---
        if self.is_train and self.in_the_wild_dir and os.path.exists(self.in_the_wild_dir):
            meta_csv = os.path.join(self.in_the_wild_dir, "meta.csv")
            if os.path.exists(meta_csv):
                itw_df = pd.read_csv(meta_csv)
                itw_real = 0
                itw_fake = 0
                for _, row in itw_df.iterrows():
                    wav_path = os.path.join(self.in_the_wild_dir, row["file"])
                    if not os.path.exists(wav_path):
                        continue
                    if row["label"] == "bona-fide":
                        real_samples.append((wav_path, 0))
                        itw_real += 1
                    else:
                        fake_samples.append((wav_path, 1))
                        itw_fake += 1
                print(f"  🌍 In-the-Wild: +{itw_real} REAL, +{itw_fake} FAKE")
            else:
                print(f"  ⚠️ Nie znaleziono meta.csv w {self.in_the_wild_dir}")

        # --- Balansowanie klas (downsampling FAKE) ---
        if self.is_train and self.fake_to_real_ratio > 0:
            max_fake = int(len(real_samples) * self.fake_to_real_ratio)
            if len(fake_samples) > max_fake:
                random.seed(42)  # Reproducibility
                fake_samples = random.sample(fake_samples, max_fake)
                print(f"  ⚖️  Downsampling FAKE: {len(fake_samples)} (z ratio {self.fake_to_real_ratio}:1)")

        samples = real_samples + fake_samples
        if self.is_train:
            random.shuffle(samples)

        print(f"  📊 Dataset: {len(real_samples)} REAL + {len(fake_samples)} FAKE = {len(samples)} total")
        return samples

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        file_path, target = self.samples[idx]

        # Explicitly use soundfile directly to avoid torchaudio/torchcodec DLL issues on Windows
        audio_data, sr = sf.read(file_path, dtype='float32')
        
        # sf.read returns (time,) for mono or (time, channels) for stereo
        if audio_data.ndim == 1:
            waveform = torch.tensor(audio_data).unsqueeze(0)  # [1, time]
        else:
            waveform = torch.tensor(audio_data).t()  # [channels, time]

        # Konwersja na mono
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
    def __init__(
        self,
        data_root,
        extra_fake_dir=None,
        in_the_wild_dir=None,
        batch_size=16,
        num_workers=4,
        target_sr=16000,
        fake_to_real_ratio=1.0,
    ):
        """
        Args:
            in_the_wild_dir: Ścieżka do rozpakowanego In-the-Wild datasetu.
            fake_to_real_ratio: Stosunek FAKE:REAL w zbiorze treningowym.
                1.0 = równa liczba (domyślne), 2.0 = 2x więcej FAKE.
                Nie wpływa na zbiory val/test.
        """
        super().__init__()
        self.data_root = data_root
        self.extra_fake_dir = extra_fake_dir
        self.in_the_wild_dir = in_the_wild_dir
        self.batch_size = batch_size
        self.num_workers = num_workers
        self.target_sr = target_sr
        self.fake_to_real_ratio = fake_to_real_ratio
        self.augmentations = get_audio_augmentations()

    def setup(self, stage=None):
        if stage == "fit" or stage is None:
            print("\n📦 Ładowanie zbioru TRENINGOWEGO:")
            self.train_dataset = ASVspoofDataset(
                protocols_file=os.path.join(self.data_root, "ASVspoof2019_LA_cm_protocols", "ASVspoof2019.LA.cm.train.trn.txt"),
                data_dir=os.path.join(self.data_root, "ASVspoof2019_LA_train", "flac"),
                extra_fake_dir=self.extra_fake_dir,
                in_the_wild_dir=self.in_the_wild_dir,
                transform=self.augmentations,
                is_train=True,
                target_sr=self.target_sr,
                fake_to_real_ratio=self.fake_to_real_ratio,
            )
            print("\n📦 Ładowanie zbioru WALIDACYJNEGO:")
            self.val_dataset = ASVspoofDataset(
                protocols_file=os.path.join(self.data_root, "ASVspoof2019_LA_cm_protocols", "ASVspoof2019.LA.cm.dev.trl.txt"),
                data_dir=os.path.join(self.data_root, "ASVspoof2019_LA_dev", "flac"),
                extra_fake_dir=None,
                is_train=False,
                target_sr=self.target_sr,
            )
        if stage == "test" or stage is None:
            print("\n📦 Ładowanie zbioru TESTOWEGO:")
            self.test_dataset = ASVspoofDataset(
                protocols_file=os.path.join(self.data_root, "ASVspoof2019_LA_cm_protocols", "ASVspoof2019.LA.cm.eval.trl.txt"),
                data_dir=os.path.join(self.data_root, "ASVspoof2019_LA_eval", "flac"),
                extra_fake_dir=None,
                is_train=False,
                target_sr=self.target_sr
            )

    def train_dataloader(self):
        return DataLoader(self.train_dataset, batch_size=self.batch_size, shuffle=True, num_workers=self.num_workers)

    def val_dataloader(self):
        return DataLoader(self.val_dataset, batch_size=self.batch_size, shuffle=False, num_workers=self.num_workers)

    def test_dataloader(self):
        return DataLoader(self.test_dataset, batch_size=self.batch_size, shuffle=False, num_workers=self.num_workers)
