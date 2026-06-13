import argparse
import importlib
import sys
from pathlib import Path
import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns
import torch
from sklearn.metrics import (
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
    roc_curve,
)
from tqdm import tqdm


def compute_eer(y_true, y_score):
    fpr, tpr, thresholds = roc_curve(y_true, y_score)
    fnr = 1 - tpr
    idx = np.nanargmin(np.absolute((fnr - fpr)))
    eer_threshold = thresholds[idx]
    eer = fpr[idx]
    return eer, eer_threshold


def main():
    parser = argparse.ArgumentParser(
        description="Shared Evaluation Framework for Audio/Video Deepfake Detection"
    )
    parser.add_argument("--model", type=str, required=True, help="Path to checkpoint (.ckpt)")
    parser.add_argument(
        "--dataset", type=str, required=True, help="Path to the dataset / cache dir"
    )
    parser.add_argument(
        "--model_class",
        type=str,
        required=False,
        help="Full module path to LightningModule (e.g. training.train_mel.MelCNNLightningModule)",
    )
    parser.add_argument(
        "--data_module",
        type=str,
        required=False,
        help="Full module path to DataModule (e.g. training.datasets.ParquetASVspoofDataModule)",
    )
    parser.add_argument("--batch_size", type=int, default=16, help="Batch size for evaluation")
    parser.add_argument(
        "--limit_batches",
        type=int,
        default=0,
        help="Ogranicz liczbe batchy (0 = brak limitu, np. 50 = szybki test)",
    )
    parser.add_argument(
        "--device", type=str, default="cuda" if torch.cuda.is_available() else "cpu"
    )
    parser.add_argument(
        "--output_dir", type=str, default="eval_results", help="Directory to save generated plots"
    )
    args = parser.parse_args()
    Path(args.output_dir).mkdir(parents=True, exist_ok=True)
    if not args.data_module:
        args.data_module = "training.datasets.ASVspoofDataModule"
    if not args.model_class:
        if "mel" in args.model.lower():
            args.model_class = "training.train_mel.MelCNNLightningModule"
        elif "w2v" in args.model.lower() or "wav2vec" in args.model.lower():
            args.model_class = "training.train_w2v2.Wav2Vec2LightningModule"
        else:
            print(
                "ERROR: Could not auto-detect --model_class based on the model filename. Please provide it explicitly."
            )
            sys.exit(1)
    print("=" * 50)
    print(" DEEPFAKE EVALUATION FRAMEWORK ")
    print("=" * 50)
    print(f"Device:      {args.device}")
    print(f"Model Class: {args.model_class}")
    print(f"Data Module: {args.data_module}")
    print("-" * 50)
    print(f"Loading dataset from: {args.dataset}")
    data_mod_parts = args.data_module.split(".")
    data_module_cls = getattr(
        importlib.import_module(".".join(data_mod_parts[:-1])), data_mod_parts[-1]
    )
    dm = data_module_cls(args.dataset, batch_size=args.batch_size)
    dm.setup(stage="test")
    test_loader = dm.test_dataloader()
    if args.limit_batches > 0:
        print("Wymuszanie losowania (shuffle) dla limit_batches...")
        test_loader = torch.utils.data.DataLoader(
            test_loader.dataset,
            batch_size=args.batch_size,
            shuffle=True,
            num_workers=test_loader.num_workers,
        )
    print(f"Loading checkpoint from: {args.model}")
    model_mod_parts = args.model_class.split(".")
    model_cls = getattr(
        importlib.import_module(".".join(model_mod_parts[:-1])), model_mod_parts[-1]
    )
    model = model_cls.load_from_checkpoint(args.model, map_location=args.device)
    model.to(args.device)
    model.eval()
    all_preds = []
    all_targets = []
    print("Running inference on test dataset...")
    with torch.no_grad():
        for i, batch in enumerate(tqdm(test_loader, desc="Evaluating")):
            if args.limit_batches > 0 and i >= args.limit_batches:
                break
            x, y = batch
            x = x.to(args.device)
            logits = model(x)
            probs = torch.sigmoid(logits).squeeze()
            if probs.ndim == 0:
                probs = probs.unsqueeze(0)
            all_preds.extend(probs.cpu().numpy())
            all_targets.extend(y.cpu().numpy())
    all_preds = np.array(all_preds)
    all_targets = np.array(all_targets)
    binary_preds = (all_preds > 0.5).astype(int)
    precision = precision_score(all_targets, binary_preds, zero_division=0)
    recall = recall_score(all_targets, binary_preds, zero_division=0)
    f1 = f1_score(all_targets, binary_preds, zero_division=0)
    roc_auc = roc_auc_score(all_targets, all_preds)
    eer, eer_threshold = compute_eer(all_targets, all_preds)
    accuracy = (binary_preds == all_targets).mean()
    print("\n" + "=" * 50)
    print(" EVALUATION RESULTS ")
    print("=" * 50)
    print(f"Accuracy:  {accuracy:.4f}")
    print(f"Precision: {precision:.4f}")
    print(f"Recall:    {recall:.4f}")
    print(f"F1 Score:  {f1:.4f}")
    print(f"ROC-AUC:   {roc_auc:.4f}")
    print(f"EER:       {eer * 100:.2f}%  (threshold: {eer_threshold:.4f})")
    print("=" * 50)
    fpr, tpr, _ = roc_curve(all_targets, all_preds)
    plt.figure()
    plt.plot(fpr, tpr, color="darkorange", lw=2, label=f"ROC curve (area = {roc_auc:.4f})")
    plt.plot([0, 1], [0, 1], color="navy", lw=2, linestyle="--")
    plt.xlim([0.0, 1.0])
    plt.ylim([0.0, 1.05])
    plt.xlabel("False Positive Rate")
    plt.ylabel("True Positive Rate")
    plt.title("Receiver Operating Characteristic")
    plt.legend(loc="lower right")
    plt.grid(True, alpha=0.3)
    roc_path = Path(args.output_dir) / "roc_curve.png"
    plt.savefig(roc_path, bbox_inches="tight", dpi=300)
    plt.close()
    cm = confusion_matrix(all_targets, binary_preds)
    plt.figure(figsize=(6, 5))
    sns.heatmap(
        cm,
        annot=True,
        fmt="d",
        cmap="Blues",
        xticklabels=["REAL (0)", "FAKE (1)"],
        yticklabels=["REAL (0)", "FAKE (1)"],
    )
    plt.ylabel("True label")
    plt.xlabel("Predicted label")
    plt.title("Confusion Matrix")
    cm_path = Path(args.output_dir) / "confusion_matrix.png"
    plt.savefig(cm_path, bbox_inches="tight", dpi=300)
    plt.close()
    print(f"Plots saved to directory: {args.output_dir}/")


if __name__ == "__main__":
    main()
