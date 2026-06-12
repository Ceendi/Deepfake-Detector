"""Export wytrenowanego modelu do ONNX — dwa osobne grafy zamiast jednego.

    backbone.onnx  (B, 3, 224, 224) -> (B, 1792)   dynamiczny batch
    temporal.onnx  (B, T, 1792)     -> (B, 1)      dynamiczny batch i czas

Rozdzielenie jest celowe (raport MO):
- micro-batching per-klatka: klatki z ROZNYCH analiz mozna sklejac w jeden
  batch CNN (2-3x throughput na CPU),
- early stopping inferencji: po 8 klatkach mozna policzyc temporal.onnx
  (dynamiczna os T) i short-circuitowac przy confidence > 0.95,
- Grad-CAM++ i tak potrzebuje grafu PyTorch (ostatnia konwolucja B4) —
  produkcja trzyma .ckpt obok .onnx wylacznie do heatmap.

    python export_onnx.py --checkpoint checkpoints/effnet_lstm/last.ckpt
"""

import argparse
import os

import torch

try:
    from model import VideoLightningModule, SEQ_LEN, IMG_SIZE
except ImportError:
    from .model import VideoLightningModule, SEQ_LEN, IMG_SIZE

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def main():
    parser = argparse.ArgumentParser(description="Export video model to ONNX")
    parser.add_argument("--checkpoint",
                        default=os.path.join(SCRIPT_DIR, "checkpoints", "effnet_lstm", "last.ckpt"))
    parser.add_argument("--out-dir", default=None, help="Domyslnie katalog checkpointu")
    args = parser.parse_args()

    if not os.path.exists(args.checkpoint):
        print(f"BLAD: nie znaleziono checkpointu {args.checkpoint}")
        raise SystemExit(1)
    out_dir = args.out_dir or os.path.dirname(args.checkpoint)
    os.makedirs(out_dir, exist_ok=True)

    print(f"Ladowanie checkpointu: {args.checkpoint}")
    module = VideoLightningModule.load_from_checkpoint(
        args.checkpoint, map_location=torch.device("cpu"), pretrained=False,
    )
    module.eval()
    backbone = module.model.backbone
    temporal = module.model.temporal
    feat_dim = backbone.num_features

    backbone_path = os.path.join(out_dir, "backbone.onnx")
    print(f"Export CNN -> {backbone_path}")
    torch.onnx.export(
        backbone,
        torch.randn(1, 3, IMG_SIZE, IMG_SIZE),
        backbone_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["frames"],
        output_names=["features"],
        dynamic_axes={"frames": {0: "batch"}, "features": {0: "batch"}},
    )

    temporal_path = os.path.join(out_dir, "temporal.onnx")
    print(f"Export Bi-LSTM + head -> {temporal_path}")
    torch.onnx.export(
        temporal,
        torch.randn(1, SEQ_LEN, feat_dim),
        temporal_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["features"],
        output_names=["logits"],
        dynamic_axes={"features": {0: "batch", 1: "time"}, "logits": {0: "batch"}},
    )

    # sanity check: ONNX vs PyTorch na tym samym wejsciu
    import numpy as np
    import onnxruntime as ort

    clip = torch.randn(2, SEQ_LEN, 3, IMG_SIZE, IMG_SIZE)
    with torch.no_grad():
        ref = module.model(clip).numpy()
    sess_b = ort.InferenceSession(backbone_path, providers=["CPUExecutionProvider"])
    sess_t = ort.InferenceSession(temporal_path, providers=["CPUExecutionProvider"])
    feats = sess_b.run(None, {"frames": clip.flatten(0, 1).numpy()})[0].reshape(2, SEQ_LEN, feat_dim)
    out = sess_t.run(None, {"features": feats})[0]
    max_diff = float(np.abs(ref - out).max())
    print(f"Sanity check ONNX vs PyTorch: max |diff| = {max_diff:.6f}")
    if max_diff > 1e-3:
        print("UWAGA: rozjazd wiekszy niz 1e-3 — sprawdz export!")
    else:
        print("Export zakonczony sukcesem!")


if __name__ == "__main__":
    main()
