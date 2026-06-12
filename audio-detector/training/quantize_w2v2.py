"""Quantize the exported Wav2Vec2 ONNX model to INT8 (dynamic, ~4x smaller).

Inference is CPU-only. INT8 dynamic shrinks the model ~4x AND is accelerated on
CPU (AVX2/VNNI), keeping float32 I/O so src/inference.py only needs to point at the
quantized file. FP16 was evaluated and dropped: on CPU ORT upcasts fp16 to fp32
(no speedup), and naive conversion yields an ORT-invalid graph on this transformer.

Run with --probe for a quick synthetic fidelity check (score drift vs fp32). The
real EER impact must be measured against the ASVspoof eval set.
"""
import argparse
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
W2V2_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "w2v2")
FP32 = os.path.join(W2V2_DIR, "w2v2.onnx")
INT8 = os.path.join(W2V2_DIR, "w2v2_int8.onnx")


def mb(path):
    return os.path.getsize(path) / (1024 * 1024)


def quantize():
    if not os.path.exists(FP32):
        raise SystemExit(f"Not found: {FP32}")
    from onnxruntime.quantization import QuantType, quantize_dynamic

    # Dynamic quant: weights -> int8, activations quantized at runtime. No calibration
    # data needed; good fit for the transformer's MatMul/Linear layers.
    quantize_dynamic(FP32, INT8, weight_type=QuantType.QInt8)
    print(f"FP32 : {mb(FP32):8.1f} MB")
    print(f"INT8 : {mb(INT8):8.1f} MB  ({mb(FP32) / mb(INT8):.2f}x smaller)")


def probe():
    """Synthetic fidelity check: score drift int8 vs fp32 and verdict flips at 0.5.
    NOT a labelled EER — a numerical-fidelity proxy on varied 1s @16kHz signals."""
    import numpy as np
    import onnxruntime as ort

    def sess(p):
        return ort.InferenceSession(p, providers=["CPUExecutionProvider"])

    s32, s8 = sess(FP32), sess(INT8)
    rng = np.random.default_rng(0)
    xs = [(rng.standard_normal((1, 16000)).astype(np.float32) * a) for a in (0.01, 0.05, 0.2, 0.5, 1.0)]
    t = np.linspace(0, 1, 16000, dtype=np.float32)
    xs += [(0.5 * np.sin(2 * np.pi * f * t)).reshape(1, 16000).astype(np.float32) for f in (120, 300, 800, 2000)]

    def score(s, x):
        z = np.asarray(s.run(None, {"input_values": x})[0]).reshape(-1)[0]
        return 1.0 / (1.0 + np.exp(-z))

    base = np.array([score(s32, x) for x in xs])
    q = np.array([score(s8, x) for x in xs])
    diff = np.abs(q - base)
    flips = int(np.sum((q > 0.5) != (base > 0.5)))
    print(f"FP32 scores: min={base.min():.4f} max={base.max():.4f}")
    print(f"INT8: score MAE={diff.mean():.5f}  max={diff.max():.5f}  verdict_flips={flips}/{len(base)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", action="store_true", help="run ONLY the synthetic fidelity check on existing files")
    args = ap.parse_args()
    probe() if args.probe else quantize()


if __name__ == "__main__":
    main()
