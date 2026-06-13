"""Trening EfficientNet-B4 + Bi-LSTM na cache'u FF++ (Colab Pro T4 / Kaggle).

Setup wg briefu: AdamW z discriminative LR (backbone 1e-5, LSTM+head 1e-3),
OneCycleLR, fp16 mixed precision (bez tego OOM na T4), batch 8 z gradient
accumulation 2x, early stopping na val F1, max 20 epok.

    python train.py --cache-dir data/ffpp_cache --wandb deepfake-detector

Przy OOM na T4: --batch-size 4 --accumulate 4 --grad-checkpointing.
"""

import argparse
import os

import torch
import lightning.pytorch as pl
from lightning.pytorch.callbacks import (
    EarlyStopping,
    LearningRateMonitor,
    ModelCheckpoint,
    TQDMProgressBar,
)
from lightning.pytorch.loggers import CSVLogger

try:
    from datasets import FFPPDataModule
    from model import VideoLightningModule
except ImportError:
    from .datasets import FFPPDataModule
    from .model import VideoLightningModule

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def main():
    parser = argparse.ArgumentParser(description="Video deepfake detector training")
    parser.add_argument("--cache-dir", default=os.path.join(SCRIPT_DIR, "data", "ffpp_cache"))
    parser.add_argument("--checkpoint-dir", default=os.path.join(SCRIPT_DIR, "checkpoints", "effnet_lstm"))
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--accumulate", type=int, default=2, help="Gradient accumulation steps")
    parser.add_argument("--num-workers", type=int, default=4)
    # Domyslne LR z grid searchu (training/grid_results.csv, 2026-06): backbone 5e-4
    # i head 3e-4 daly val_auc 0.955 vs 0.736 dla wartosci z briefu (1e-5/1e-3);
    # 1e-3 na backbone juz sie wywraca. Pelna tabela i uzasadnienie w README
    parser.add_argument("--lr-backbone", type=float, default=5e-4)
    parser.add_argument("--lr-head", type=float, default=3e-4)
    parser.add_argument("--label-smoothing", type=float, default=0.1)
    parser.add_argument("--monitor", default="val_auc", choices=["val_auc", "val_f1", "val_loss"],
                        help="Metryka dla checkpoint + early stopping. Domyslnie val_auc, nie val_f1: "
                             "F1 przy progu 0.5 szczytuje sztucznie na poczatku treningu (bias w strone "
                             "FAKE przy logitach ~0) i ucina trening, gdy model dopiero zaczyna sie uczyc")
    parser.add_argument("--patience", type=int, default=7, help="Early stopping patience (epoki)")
    parser.add_argument("--grad-checkpointing", action="store_true",
                        help="Mniej VRAM kosztem ~25%% czasu kroku")
    parser.add_argument("--wandb", default=None, metavar="PROJECT",
                        help="Nazwa projektu W&B; bez flagi logi ida do CSVLogger")
    parser.add_argument("--resume", nargs="?", const="last", default=None, metavar="CKPT",
                        help="Wznow przerwany trening: --resume (= last.ckpt z --checkpoint-dir) "
                             "lub --resume sciezka/do/pliku.ckpt. Przywraca wagi, optimizer, "
                             "scheduler i numer epoki. UWAGA: uzyj tych samych --epochs co "
                             "w przerwanym runie — OneCycleLR ma harmonogram policzony na caly "
                             "trening i zmiana liczby epok przy wznowieniu rozjedzie LR")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not torch.cuda.is_available():
        print("BLAD: trening wymaga GPU (Colab Pro T4 / Kaggle). Przerwanie.")
        raise SystemExit(1)

    pl.seed_everything(args.seed, workers=True)
    os.makedirs(args.checkpoint_dir, exist_ok=True)

    data_module = FFPPDataModule(
        cache_dir=args.cache_dir,
        batch_size=args.batch_size,
        num_workers=args.num_workers,
    )
    model = VideoLightningModule(
        lr_backbone=args.lr_backbone,
        lr_head=args.lr_head,
        label_smoothing=args.label_smoothing,
        grad_checkpointing=args.grad_checkpointing,
    )

    if args.wandb:
        from lightning.pytorch.loggers import WandbLogger
        logger = WandbLogger(project=args.wandb, name="effnet_b4_bilstm")
    else:
        logger = CSVLogger(save_dir=os.path.join(SCRIPT_DIR, "logs"), name="effnet_b4_bilstm")

    monitor_mode = "min" if args.monitor == "val_loss" else "max"
    callbacks = [
        ModelCheckpoint(
            dirpath=args.checkpoint_dir,
            filename=f"video-{{epoch:02d}}-{{{args.monitor}:.4f}}",
            monitor=args.monitor,
            mode=monitor_mode,
            save_top_k=3,
            save_last=True,
        ),
        EarlyStopping(monitor=args.monitor, mode=monitor_mode, patience=args.patience),
        LearningRateMonitor(logging_interval="step"),
        TQDMProgressBar(refresh_rate=10),
    ]

    trainer = pl.Trainer(
        max_epochs=args.epochs,
        accelerator="gpu",
        devices=1,
        precision="16-mixed",
        accumulate_grad_batches=args.accumulate,
        callbacks=callbacks,
        logger=logger,
        log_every_n_steps=10,
    )

    ckpt_path = None
    if args.resume:
        ckpt_path = (os.path.join(args.checkpoint_dir, "last.ckpt")
                     if args.resume == "last" else args.resume)
        if not os.path.exists(ckpt_path):
            print(f"BLAD: brak checkpointu do wznowienia: {ckpt_path}")
            raise SystemExit(1)
        print(f"Wznawianie treningu z: {ckpt_path}")

    print("Rozpoczynanie treningu EfficientNet-B4 + Bi-LSTM...")
    trainer.fit(model, datamodule=data_module, ckpt_path=ckpt_path)
    print(f"Trening zakonczony. Najlepszy checkpoint: {callbacks[0].best_model_path}")


if __name__ == "__main__":
    main()
