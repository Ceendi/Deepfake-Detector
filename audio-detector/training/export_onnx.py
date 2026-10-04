import os

import torch

try:
    from train_w2v2 import Wav2Vec2LightningModule
except ImportError:
    from .train_w2v2 import Wav2Vec2LightningModule


def export_w2v2_to_onnx():
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    CHECKPOINT_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "w2v2")
    LAST_CKPT = os.path.join(CHECKPOINT_DIR, "last.ckpt")
    ONNX_OUTPUT = os.path.join(CHECKPOINT_DIR, "w2v2.onnx")
    if not os.path.exists(LAST_CKPT):
        print(f"BŁĄD: Nie znaleziono checkpointu {LAST_CKPT}!")
        return
    print(f"Ładowanie modelu Wav2Vec2 z checkpointu: {LAST_CKPT}...")
    model = Wav2Vec2LightningModule.load_from_checkpoint(
        LAST_CKPT, map_location=torch.device("cpu")
    )
    model.eval()
    export_model(model, ONNX_OUTPUT)
    print("Eksport zakończony sukcesem!")


def export_model(model, output_path, sample_length=16000):
    """Export the classifier with variable batch and waveform lengths."""
    dummy_input = torch.randn(1, sample_length)
    torch.onnx.export(
        model,
        dummy_input,
        output_path,
        export_params=True,
        # Preserve the dynamic axes contract used by the ONNX inference service.
        dynamo=False,
        opset_version=14,
        do_constant_folding=True,
        input_names=["input_values"],
        output_names=["logits"],
        dynamic_axes={
            "input_values": {0: "batch_size", 1: "sequence_length"},
            "logits": {0: "batch_size"},
        },
    )


if __name__ == "__main__":
    export_w2v2_to_onnx()
