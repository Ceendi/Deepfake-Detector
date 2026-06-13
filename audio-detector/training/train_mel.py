import os
import torch
import torch.nn as nn
import torch.nn.functional as F
from torchvision import models
import lightning.pytorch as pl
from lightning.pytorch.callbacks import ModelCheckpoint, TQDMProgressBar, EarlyStopping
import torchaudio
from sklearn.metrics import roc_curve
import numpy as np

try:
    from datasets import ASVspoofDataModule
except ImportError:
    from .datasets import ASVspoofDataModule
torch.set_float32_matmul_precision("high")


def compute_eer(y_true, y_score):
    fpr, tpr, thresholds = roc_curve(y_true, y_score)
    fnr = 1 - tpr
    idx = np.nanargmin(np.absolute((fnr - fpr)))
    eer = fpr[idx]
    return eer


class MelResNetGRU(nn.Module):
    def __init__(self):
        super().__init__()
        self.resnet = models.resnet18(weights=None)
        self.resnet.conv1 = nn.Conv2d(1, 64, kernel_size=7, stride=2, padding=3, bias=False)
        self.gru = nn.GRU(input_size=512, hidden_size=64, bidirectional=True, batch_first=True)
        self.dropout = nn.Dropout(0.3)
        self.classifier = nn.Linear(128, 1)

    def forward(self, x):
        x = self.resnet.conv1(x)
        x = self.resnet.bn1(x)
        x = self.resnet.relu(x)
        x = self.resnet.maxpool(x)
        x = self.resnet.layer1(x)
        x = self.resnet.layer2(x)
        x = self.resnet.layer3(x)
        x = self.resnet.layer4(x)
        x = F.adaptive_avg_pool2d(x, (1, None))
        x = x.squeeze(2)
        x = x.permute(0, 2, 1)
        x, _ = self.gru(x)
        x = x.mean(dim=1)
        x = self.dropout(x)
        x = self.classifier(x)
        return x


class MelCNNLightningModule(pl.LightningModule):
    def __init__(self, learning_rate=1e-3):
        super().__init__()
        self.save_hyperparameters()
        self.model = MelResNetGRU()
        self.learning_rate = learning_rate
        self.mel_transform = torchaudio.transforms.MelSpectrogram(
            sample_rate=16000, n_fft=2048, hop_length=512, n_mels=128
        )
        self.amp_to_db = torchaudio.transforms.AmplitudeToDB()
        self.validation_step_outputs = []

    def forward(self, x):
        if x.dim() == 1:
            x = x.unsqueeze(0)
        mean = x.mean(dim=-1, keepdim=True)
        std = x.std(dim=-1, keepdim=True)
        x = (x - mean) / (std + 1e-7)
        mel = self.mel_transform(x)
        mel_db = self.amp_to_db(mel)
        mel_db = mel_db.unsqueeze(1)
        return self.model(mel_db)

    def training_step(self, batch, batch_idx):
        x, y = batch
        if torch.rand(1).item() < 0.3:
            lam = torch.distributions.Beta(0.4, 0.4).sample().to(x.device)
            idx = torch.randperm(x.size(0), device=x.device)
            x = lam * x + (1 - lam) * x[idx]
            y = lam * y + (1 - lam) * y[idx]
        logits = self(x).squeeze(-1)
        y_smooth = y * 0.9 + 0.05
        loss = F.binary_cross_entropy_with_logits(logits, y_smooth)
        self.log("train_loss", loss, on_step=True, on_epoch=True, prog_bar=True, logger=True)
        return loss

    def validation_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(-1)
        loss = F.binary_cross_entropy_with_logits(logits, y)
        preds = torch.sigmoid(logits)
        self.validation_step_outputs.append((preds.detach().cpu(), y.detach().cpu()))
        self.log("val_loss", loss, on_epoch=True, prog_bar=True, logger=True)
        return loss

    def on_validation_epoch_end(self):
        all_preds = torch.cat([out[0] for out in self.validation_step_outputs]).float().numpy()
        all_targets = torch.cat([out[1] for out in self.validation_step_outputs]).float().numpy()
        if len(np.unique(all_targets)) > 1:
            eer = compute_eer(all_targets, all_preds)
            self.log("val_eer", eer, prog_bar=True, logger=True)
            acc = ((all_preds > 0.5) == all_targets).mean()
            self.log("val_acc", acc, prog_bar=True, logger=True)
        self.validation_step_outputs.clear()

    def configure_optimizers(self):
        optimizer = torch.optim.AdamW(self.parameters(), lr=self.learning_rate)
        scheduler = torch.optim.lr_scheduler.CosineAnnealingWarmRestarts(
            optimizer,
            T_0=5,
            T_mult=1,
            eta_min=1e-6,
        )
        return {
            "optimizer": optimizer,
            "lr_scheduler": {
                "scheduler": scheduler,
                "interval": "epoch",
                "frequency": 1,
            },
        }


if __name__ == "__main__":
    if not torch.cuda.is_available():
        print("BŁĄD: Karta graficzna (GPU) nie została wykryta! Trening modelu Mel-CNN wymaga GPU.")
        print("Przerwanie działania skryptu.")
        exit(1)
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    DATA_ROOT = os.path.join(SCRIPT_DIR, "data/archive/LA/LA")
    EXTRA_FAKE_DIR = os.path.join(SCRIPT_DIR, "data/generated_audio")
    IN_THE_WILD_DIR = os.path.join(SCRIPT_DIR, "data/release_in_the_wild/release_in_the_wild")
    CHECKPOINT_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "mel_resnet")
    os.makedirs(CHECKPOINT_DIR, exist_ok=True)
    data_module = ASVspoofDataModule(
        data_root=DATA_ROOT,
        extra_fake_dir=EXTRA_FAKE_DIR,
        in_the_wild_dir=IN_THE_WILD_DIR,
        batch_size=32,
        num_workers=8,
        fake_to_real_ratio=1.0,
    )
    model = MelCNNLightningModule(learning_rate=5e-4)
    checkpoint_callback = ModelCheckpoint(
        dirpath=CHECKPOINT_DIR,
        filename="mel_model-{epoch:02d}-{val_eer:.4f}",
        monitor="val_eer",
        mode="min",
        save_top_k=3,
        save_last=True,
    )
    early_stop_callback = EarlyStopping(
        monitor="val_eer",
        mode="min",
        patience=10,
        min_delta=0.001,
        verbose=True,
    )
    progress_bar = TQDMProgressBar(refresh_rate=10)
    trainer = pl.Trainer(
        max_epochs=50,
        callbacks=[checkpoint_callback, early_stop_callback, progress_bar],
        accelerator="auto",
        devices=1,
        precision="bf16-mixed",
        log_every_n_steps=10,
    )
    import glob

    best_ckpt = None
    ckpts = glob.glob(os.path.join(CHECKPOINT_DIR, "mel_model-*.ckpt"))
    if ckpts:
        best_ckpt = min(ckpts, key=lambda p: float(p.split("val_eer=")[1].replace(".ckpt", "")))
    if best_ckpt:
        print(f"Ładowanie wag z najlepszego checkpointu: {best_ckpt}...")
        loaded = MelCNNLightningModule.load_from_checkpoint(best_ckpt, map_location="cpu")
        model.load_state_dict(loaded.state_dict(), strict=False)
        del loaded
        print(
            "Wagi załadowane. Rozpoczynanie NOWEJ fazy treningu (nowy scheduler + regularyzacja)..."
        )
    else:
        print("Rozpoczynanie treningu modelu Mel-CNN+GRU od zera...")
    trainer.fit(model, datamodule=data_module)
    print("Trening zakończony!")
