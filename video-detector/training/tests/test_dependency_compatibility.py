"""Check checkpoint and gradient compatibility without downloading ML weights."""

import lightning
import torch

from training.model import VideoLightningModule


def test_video_checkpoint_attention_gradient_and_reload(tmp_path):
    torch.manual_seed(7)
    model = VideoLightningModule(pretrained=False, lstm_hidden=8).eval()
    features = torch.randn(1, 3, model.model.backbone.num_features, requires_grad=True)
    logits = model.model.temporal(features)
    assert logits.shape == (1, 1)
    logits.sum().backward()
    assert torch.isfinite(features.grad).all()
    with torch.no_grad():
        sequence, _ = model.model.temporal.lstm(features)
        _, attention = model.model.temporal.pool(sequence)
    torch.testing.assert_close(attention.sum(dim=1), torch.ones(1))
    checkpoint = tmp_path / 'model.ckpt'
    torch.save({
        'state_dict': model.state_dict(), 'hyper_parameters': dict(model.hparams),
        'pytorch-lightning_version': lightning.__version__,
    }, checkpoint)
    restored = VideoLightningModule.load_from_checkpoint(checkpoint, map_location='cpu').eval()
    with torch.no_grad():
        torch.testing.assert_close(restored.model.temporal(features.detach()), logits.detach())
