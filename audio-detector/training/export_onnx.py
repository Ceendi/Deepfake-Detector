import os
import torch
from train_w2v2 import Wav2Vec2LightningModule
def export_w2v2_to_onnx():
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    CHECKPOINT_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "w2v2")
    LAST_CKPT = os.path.join(CHECKPOINT_DIR, "last.ckpt")
    ONNX_OUTPUT = os.path.join(CHECKPOINT_DIR, "w2v2.onnx")
    if not os.path.exists(LAST_CKPT):
        print(f"BŁĄD: Nie znaleziono checkpointu {LAST_CKPT}!")
        return
    print(f"Ładowanie modelu Wav2Vec2 z checkpointu: {LAST_CKPT}...")
    model = Wav2Vec2LightningModule.load_from_checkpoint(LAST_CKPT, map_location=torch.device('cpu'))
    model.eval()
    dummy_input = torch.randn(1, 16000)
    print(f"Eksportowanie modelu do formatu ONNX do pliku: {ONNX_OUTPUT}...")
    torch.onnx.export(
        model,
        dummy_input,
        ONNX_OUTPUT,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['input_values'],
        output_names=['logits'],
        dynamic_axes={
            'input_values': {0: 'batch_size', 1: 'sequence_length'},
            'logits': {0: 'batch_size'}
        }
    )
    print("Eksport zakończony sukcesem!")
if __name__ == "__main__":
    export_w2v2_to_onnx()
