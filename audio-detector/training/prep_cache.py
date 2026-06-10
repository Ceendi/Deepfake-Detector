import os

import torchaudio
import pandas as pd
from tqdm import tqdm
from datasets import ASVspoofDataset
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_ROOT = os.path.join(SCRIPT_DIR, "data/archive/LA/LA")
CACHE_DIR = os.path.join(SCRIPT_DIR, "data/data_cache")
os.makedirs(CACHE_DIR, exist_ok=True)
mel_transform = torchaudio.transforms.MelSpectrogram(
    sample_rate=16000,
    n_fft=2048,
    hop_length=512,
    n_mels=128
)
def build_cache(split="train"):
    cache_path = os.path.join(CACHE_DIR, f"melspec_{split}.parquet")
    if os.path.exists(cache_path):
        print(f"Cache dla '{split}' już istnieje ({cache_path}). Pomijanie...")
        return
    print("Building cache...")
    if split == "train":
        protocol = "ASVspoof2019.LA.cm.train.trn.txt"
        audio_dir = "ASVspoof2019_LA_train"
    elif split == "dev":
        protocol = "ASVspoof2019.LA.cm.dev.trl.txt"
        audio_dir = "ASVspoof2019_LA_dev"
    else:
        protocol = "ASVspoof2019.LA.cm.eval.trl.txt"
        audio_dir = "ASVspoof2019_LA_eval"
    dataset = ASVspoofDataset(
        protocols_file=os.path.join(DATA_ROOT, "ASVspoof2019_LA_cm_protocols", protocol),
        data_dir=os.path.join(DATA_ROOT, audio_dir, "flac"),
        transform=None,
        is_train=False,
        target_sr=16000
    )
    features_list = []
    labels_list = []
    for i in tqdm(range(len(dataset)), desc=f"Processing {split} set"):
        signal, label = dataset[i]
        mel_spec = mel_transform(signal)
        features_list.append(mel_spec.numpy().flatten())
        labels_list.append(label.item())
    print("Saving cache...")
    df = pd.DataFrame({
        "features": features_list,
        "label": labels_list
    })
    cache_path = os.path.join(CACHE_DIR, f"melspec_{split}.parquet")
    df.to_parquet(cache_path, engine="pyarrow")
    print(f"Cache saved to {cache_path}")
if __name__ == "__main__":
    build_cache("train")
    build_cache("dev")
    build_cache("eval")
