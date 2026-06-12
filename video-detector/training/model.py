"""Architektura modelu video deepfake: EfficientNet-B4 + Bi-LSTM + attention pooling.

Przeplyw danych (pelna analiza: training/README.md):

    wejscie  (B, T=16, 3, 224, 224)   — 16 wyrownanych cropow twarzy z klipu
    backbone EfficientNet-B4 (timm, global avg pool, num_classes=0)
             (B*T, 3, 224, 224) -> (B*T, 1792) -> reshape (B, T, 1792)
    temporal Bi-LSTM(hidden=128, bidirectional) -> (B, T, 256)
             attention pooling po osi czasu     -> (B, 256)
    head     Dropout(0.3) -> Linear(256, 64) -> ReLU -> Linear(64, 1)
    wyjscie  (B, 1) logit; sigmoid(logit) = prob_fake

Backbone i czesc temporalna sa rozdzielone (VideoDeepfakeNet.backbone /
.temporal), zeby export_onnx.py mogl wyeksportowac dwa osobne grafy ONNX —
inferencja produkcyjna liczy cechy per-klatka (micro-batching, early-stop po
8 klatkach przy confidence > 0.95), a czesc temporalna dostaje gotowa
sekwencje cech.
"""

import torch
import torch.nn as nn
import torch.nn.functional as F
import lightning.pytorch as pl
import timm
from torchmetrics.classification import BinaryAccuracy, BinaryAUROC, BinaryF1Score

SEQ_LEN = 16
IMG_SIZE = 224


class AttentionPooling(nn.Module):
    """Uczona suma wazona po osi czasu: score -> softmax -> srednia wazona.

    Zwraca tez wagi atencji — przydatne do wyboru top-3 klatek pod Grad-CAM
    (klatki, na ktore model najmocniej "patrzyl").
    """

    def __init__(self, dim: int):
        super().__init__()
        self.score = nn.Sequential(nn.Linear(dim, 64), nn.Tanh(), nn.Linear(64, 1))

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        # x: (B, T, D)
        weights = torch.softmax(self.score(x), dim=1)          # (B, T, 1)
        pooled = (weights * x).sum(dim=1)                      # (B, D)
        return pooled, weights.squeeze(-1)                     # (B, D), (B, T)


class TemporalClassifier(nn.Module):
    """Bi-LSTM + attention pooling + MLP head. Wejscie: sekwencja cech CNN."""

    def __init__(self, feat_dim: int = 1792, lstm_hidden: int = 128, dropout: float = 0.3):
        super().__init__()
        self.lstm = nn.LSTM(
            input_size=feat_dim,
            hidden_size=lstm_hidden,
            batch_first=True,
            bidirectional=True,
        )
        self.pool = AttentionPooling(2 * lstm_hidden)
        self.head = nn.Sequential(
            nn.Dropout(dropout),
            nn.Linear(2 * lstm_hidden, 64),
            nn.ReLU(),
            nn.Linear(64, 1),
        )

    def forward(self, feats: torch.Tensor) -> torch.Tensor:
        # feats: (B, T, feat_dim)
        seq, _ = self.lstm(feats)                              # (B, T, 256)
        pooled, _ = self.pool(seq)                             # (B, 256)
        return self.head(pooled)                               # (B, 1)


class VideoDeepfakeNet(nn.Module):
    def __init__(
        self,
        backbone_name: str = "efficientnet_b4",
        pretrained: bool = True,
        lstm_hidden: int = 128,
        dropout: float = 0.3,
    ):
        super().__init__()
        self.backbone = timm.create_model(backbone_name, pretrained=pretrained, num_classes=0)
        self.temporal = TemporalClassifier(
            feat_dim=self.backbone.num_features,  # 1792 dla B4
            lstm_hidden=lstm_hidden,
            dropout=dropout,
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (B, T, 3, H, W) — klatki splaszczane do jednego batcha CNN
        b, t = x.shape[:2]
        feats = self.backbone(x.flatten(0, 1)).view(b, t, -1)  # (B, T, 1792)
        return self.temporal(feats)                            # (B, 1)


class VideoLightningModule(pl.LightningModule):
    """Trening wg briefu (sekcja 5.1 planu):

    - BCEWithLogitsLoss z label smoothing 0.1 (tylko w treningu)
    - discriminative LR: backbone 1e-5, LSTM+head 1e-3 (AdamW param groups)
    - OneCycleLR per-step, max 20 epok
    - monitorowana metryka: val_f1 (early stopping + checkpoint w train.py)

    Kompatybilny ze wspolnym frameworkiem ewaluacji audio-detector/eval.py
    (forward zwraca logity (B, 1), load_from_checkpoint dziala dzieki
    save_hyperparameters()).
    """

    def __init__(
        self,
        backbone_name: str = "efficientnet_b4",
        pretrained: bool = True,
        lstm_hidden: int = 128,
        dropout: float = 0.3,
        lr_backbone: float = 1e-5,
        lr_head: float = 1e-3,
        weight_decay: float = 1e-2,
        label_smoothing: float = 0.1,
        grad_checkpointing: bool = False,
    ):
        super().__init__()
        self.save_hyperparameters()
        self.model = VideoDeepfakeNet(backbone_name, pretrained, lstm_hidden, dropout)
        if grad_checkpointing:
            # ~40% mniej VRAM na aktywacjach backbone'u kosztem ~25% czasu kroku
            self.model.backbone.set_grad_checkpointing()

        self.val_f1 = BinaryF1Score()
        self.val_auc = BinaryAUROC()
        self.val_acc = BinaryAccuracy()
        self.test_f1 = BinaryF1Score()
        self.test_auc = BinaryAUROC()
        self.test_acc = BinaryAccuracy()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.model(x)

    def _smoothed_bce(self, logits: torch.Tensor, y: torch.Tensor) -> torch.Tensor:
        eps = self.hparams.label_smoothing
        y_smooth = y * (1.0 - eps) + 0.5 * eps
        return F.binary_cross_entropy_with_logits(logits, y_smooth)

    def training_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(1)
        loss = self._smoothed_bce(logits, y)
        self.log("train_loss", loss, on_step=True, on_epoch=True, prog_bar=True)
        return loss

    def validation_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(1)
        # walidacja na czystym BCE — label smoothing zawyzalby val_loss
        loss = F.binary_cross_entropy_with_logits(logits, y)
        probs = torch.sigmoid(logits)
        self.val_f1.update(probs, y.int())
        self.val_auc.update(probs, y.int())
        self.val_acc.update(probs, y.int())
        self.log("val_loss", loss, on_epoch=True, prog_bar=True)
        self.log("val_f1", self.val_f1, on_epoch=True, prog_bar=True)
        self.log("val_auc", self.val_auc, on_epoch=True)
        self.log("val_acc", self.val_acc, on_epoch=True)
        return loss

    def test_step(self, batch, batch_idx):
        x, y = batch
        probs = torch.sigmoid(self(x).squeeze(1))
        self.test_f1.update(probs, y.int())
        self.test_auc.update(probs, y.int())
        self.test_acc.update(probs, y.int())
        self.log("test_f1", self.test_f1, on_epoch=True)
        self.log("test_auc", self.test_auc, on_epoch=True)
        self.log("test_acc", self.test_acc, on_epoch=True)

    def configure_optimizers(self):
        backbone_params = list(self.model.backbone.parameters())
        head_params = list(self.model.temporal.parameters())
        optimizer = torch.optim.AdamW(
            [
                {"params": backbone_params, "lr": self.hparams.lr_backbone},
                {"params": head_params, "lr": self.hparams.lr_head},
            ],
            weight_decay=self.hparams.weight_decay,
        )
        scheduler = torch.optim.lr_scheduler.OneCycleLR(
            optimizer,
            max_lr=[self.hparams.lr_backbone, self.hparams.lr_head],
            total_steps=self.trainer.estimated_stepping_batches,
            pct_start=0.1,
        )
        return {
            "optimizer": optimizer,
            "lr_scheduler": {"scheduler": scheduler, "interval": "step"},
        }
