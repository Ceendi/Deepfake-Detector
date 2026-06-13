"""Przesiewowy grid search hiperparametrow (krotkie przebiegi, pelny cache).

Os siatki wybrana na podstawie obserwacji z lokalnych treningow: model jest
niedouczony (val_auc roslo do konca 20 epok), wiec glowna dzwignia jest tempo
uczenia backbone'u (baseline 1e-5 z briefu prawie nie rusza pretrenowanych
wag); druga os — LR glowy (LSTM+head).

Kazda proba: swiezy model, te same dane/seed, --epochs (domyslnie 6, OneCycle
skaluje sie do dlugosci runu), metryka = najlepsze val_auc. Wyniki dopisywane
do grid_results.csv PO KAZDEJ probie — skrypt mozna przerwac i wznowic ta sama
komenda (ukonczone proby sa pomijane).

    python grid_search.py --cache-dir data/ffpp_cache --epochs 6
"""

import argparse
import csv
import itertools
import os
import time

import torch
import lightning.pytorch as pl
from lightning.pytorch.callbacks import ModelCheckpoint, TQDMProgressBar
from lightning.pytorch.loggers import CSVLogger

try:
    from datasets import FFPPDataModule
    from model import VideoLightningModule
except ImportError:
    from .datasets import FFPPDataModule
    from .model import VideoLightningModule

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

GRID = {
    # 2e-4 wygralo na brzegu pierwotnej siatki [1e-5, 5e-5, 2e-4] -> dolozone 5e-4
    # (val_auc 0.955); 1e-3 to finalny check brzegowy — przy 5e-4 widac juz
    # niestabilnosc dla wyzszego LR glowy, spodziewany sufit
    "lr_backbone": [1e-5, 5e-5, 2e-4, 5e-4, 1e-3],
    "lr_head": [1e-3, 3e-4],
}

CSV_FIELDS = ["trial", "lr_backbone", "lr_head", "best_val_auc", "best_epoch",
              "last_val_f1", "minutes", "ckpt"]


def trial_name(cfg: dict) -> str:
    return "_".join(f"{k}={v:g}" for k, v in sorted(cfg.items()))


def load_done(path: str) -> set[str]:
    if not os.path.exists(path):
        return set()
    with open(path, newline="", encoding="utf-8") as f:
        return {row["trial"] for row in csv.DictReader(f)}


def append_row(path: str, row: dict) -> None:
    new_file = not os.path.exists(path)
    with open(path, "a", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        if new_file:
            writer.writeheader()
        writer.writerow(row)


def run_trial(cfg: dict, args) -> dict:
    pl.seed_everything(args.seed, workers=True)
    name = trial_name(cfg)

    data_module = FFPPDataModule(args.cache_dir, batch_size=args.batch_size,
                                 num_workers=args.num_workers)
    model = VideoLightningModule(grad_checkpointing=True, **cfg)

    ckpt_dir = os.path.join(SCRIPT_DIR, "checkpoints", "grid", name)
    ckpt_cb = ModelCheckpoint(dirpath=ckpt_dir, filename="best-{epoch:02d}-{val_auc:.4f}",
                              monitor="val_auc", mode="max", save_top_k=1)
    trainer = pl.Trainer(
        max_epochs=args.epochs,
        accelerator="gpu",
        devices=1,
        precision="16-mixed",
        accumulate_grad_batches=args.accumulate,
        callbacks=[ckpt_cb, TQDMProgressBar(refresh_rate=50)],
        logger=CSVLogger(save_dir=os.path.join(SCRIPT_DIR, "logs", "grid"), name=name),
        log_every_n_steps=10,
        enable_model_summary=False,
    )
    t0 = time.time()
    trainer.fit(model, datamodule=data_module)
    minutes = (time.time() - t0) / 60

    best_auc = float(ckpt_cb.best_model_score) if ckpt_cb.best_model_score is not None else 0.0
    best_epoch = ""
    if ckpt_cb.best_model_path:
        base = os.path.basename(ckpt_cb.best_model_path)
        best_epoch = base.split("-")[1].replace("epoch=", "")
    last_f1 = float(trainer.callback_metrics.get("val_f1", torch.tensor(float("nan"))))

    del trainer, model, data_module
    torch.cuda.empty_cache()

    return {"trial": name, **cfg, "best_val_auc": round(best_auc, 4),
            "best_epoch": best_epoch, "last_val_f1": round(last_f1, 4),
            "minutes": round(minutes, 1), "ckpt": ckpt_cb.best_model_path}


def main():
    parser = argparse.ArgumentParser(description="Grid search (screening) for video model")
    parser.add_argument("--cache-dir", default=os.path.join(SCRIPT_DIR, "data", "ffpp_cache"))
    parser.add_argument("--epochs", type=int, default=6)
    parser.add_argument("--batch-size", type=int, default=2)
    parser.add_argument("--accumulate", type=int, default=2)
    parser.add_argument("--num-workers", type=int, default=2)
    parser.add_argument("--results", default=os.path.join(SCRIPT_DIR, "grid_results.csv"))
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    if not torch.cuda.is_available():
        print("BLAD: grid search wymaga GPU.")
        raise SystemExit(1)

    combos = [dict(zip(GRID.keys(), values)) for values in itertools.product(*GRID.values())]
    done = load_done(args.results)
    todo = [c for c in combos if trial_name(c) not in done]
    print(f"Siatka: {len(combos)} prob, ukonczone wczesniej: {len(done)}, do zrobienia: {len(todo)}")

    for i, cfg in enumerate(todo, 1):
        print(f"\n===== PROBA {i}/{len(todo)}: {trial_name(cfg)} =====")
        row = run_trial(cfg, args)
        append_row(args.results, row)
        print(f"-> best val_auc={row['best_val_auc']} (epoka {row['best_epoch']}), "
              f"{row['minutes']} min")

    print("\n===== LEADERBOARD =====")
    with open(args.results, newline="", encoding="utf-8") as f:
        rows = sorted(csv.DictReader(f), key=lambda r: float(r["best_val_auc"]), reverse=True)
    for r in rows:
        print(f"val_auc={r['best_val_auc']}  epoka={r['best_epoch']:>2}  "
              f"lr_backbone={r['lr_backbone']:>7}  lr_head={r['lr_head']:>7}")


if __name__ == "__main__":
    main()
