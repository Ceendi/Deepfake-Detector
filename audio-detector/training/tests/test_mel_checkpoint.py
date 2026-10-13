"""The runtime MelCNN must load Lightning checkpoints and serve Grad-CAM without Lightning."""

import subprocess
import sys
from pathlib import Path

import lightning.pytorch as pl
import torch
from torch.utils.data import DataLoader, TensorDataset

from src.inference import AudioInference
from src.models.mel_cnn import MelCNN, load_mel_cnn
from training.train_mel import MelCNNLightningModule

PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _lightning_checkpoint(tmp_path):
    torch.manual_seed(3)
    module = MelCNNLightningModule(learning_rate=5e-4)
    data = TensorDataset(torch.randn(4, 16000), torch.tensor([0.0, 1.0, 0.0, 1.0]))
    trainer = pl.Trainer(
        accelerator="cpu", devices=1, fast_dev_run=1, default_root_dir=tmp_path,
        enable_progress_bar=False, enable_model_summary=False,
    )
    trainer.fit(module, DataLoader(data, batch_size=2), DataLoader(data, batch_size=2))
    path = tmp_path / "mel.ckpt"
    # Same writer as ModelCheckpoint: carries loops, callbacks and optimizer state too.
    trainer.save_checkpoint(path)
    return module.eval(), path


def test_runtime_model_matches_lightning_checkpoint(tmp_path):
    module, path = _lightning_checkpoint(tmp_path)

    runtime = load_mel_cnn(path)

    assert type(runtime) is MelCNN
    assert not runtime.training
    waveform = torch.randn(3, 16000)
    with torch.no_grad():
        torch.testing.assert_close(runtime(waveform), module(waveform), rtol=0, atol=0)
    # Training tools (eval.py, resumed training) still read the same file through Lightning.
    restored = MelCNNLightningModule.load_from_checkpoint(path, map_location="cpu").eval()
    with torch.no_grad():
        torch.testing.assert_close(restored(waveform), module(waveform), rtol=0, atol=0)


def test_gradcam_layer_receives_gradients(tmp_path):
    _, path = _lightning_checkpoint(tmp_path)
    inference = AudioInference.__new__(AudioInference)
    inference.mel_module = load_mel_cnn(path)
    captured = []
    hook = inference.mel_module.model.resnet.layer4.register_full_backward_hook(
        lambda _module, _grad_in, grad_out: captured.append(grad_out[0])
    )
    try:
        out = inference.generate_gradcam(torch.randn(16000), str(tmp_path / "cam.png"))
    finally:
        hook.remove()

    assert Path(out).stat().st_size > 0
    assert captured and torch.isfinite(captured[0]).all() and captured[0].abs().sum() > 0


def test_inference_import_does_not_load_training_stack():
    probe = (
        "import sys, src.inference; "
        "leaked = sorted(m for m in sys.modules if m.split('.')[0] in "
        "{'lightning', 'pytorch_lightning', 'training', 'transformers', 'pandas', 'audiomentations'}); "
        "assert not leaked, leaked"
    )
    result = subprocess.run(
        [sys.executable, "-c", probe],
        cwd=PROJECT_ROOT, capture_output=True, text=True, timeout=300, check=False,
    )
    assert result.returncode == 0, result.stderr
