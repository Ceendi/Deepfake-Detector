"""Exercise patched ML libraries using small, offline model fixtures."""

import lightning
import onnxruntime
import torch
from transformers import Wav2Vec2Config, Wav2Vec2ForSequenceClassification

from training.export_onnx import export_model
from training.train_w2v2 import Wav2Vec2LightningModule


def test_wav2vec2_checkpoint_forward_backward_and_reload(tmp_path):
    torch.manual_seed(7)
    config = Wav2Vec2Config(
        hidden_size=16, num_hidden_layers=1, num_attention_heads=2,
        intermediate_size=32, conv_dim=(8, 8, 8), conv_stride=(2, 2, 2),
        conv_kernel=(3, 3, 3), num_conv_pos_embeddings=16,
        num_conv_pos_embedding_groups=2, classifier_proj_size=8,
        num_labels=1, mask_time_prob=0.0, problem_type='multi_label_classification',
    )
    source = tmp_path / 'source'
    Wav2Vec2ForSequenceClassification(config).save_pretrained(source)
    model = Wav2Vec2LightningModule(model_name=str(source)).eval()
    assert all(not parameter.requires_grad for parameter in model.model.wav2vec2.feature_extractor.parameters())
    inputs = torch.randn(2, 512)
    logits = model(inputs)
    assert logits.shape == (2, 1)
    loss = torch.nn.functional.binary_cross_entropy_with_logits(logits, torch.tensor([[0.0], [1.0]]))
    loss.backward()
    assert torch.isfinite(model.model.classifier.weight.grad).all()
    checkpoint = tmp_path / 'model.ckpt'
    torch.save({
        'state_dict': model.state_dict(), 'hyper_parameters': dict(model.hparams),
        'pytorch-lightning_version': lightning.__version__,
    }, checkpoint)
    restored = Wav2Vec2LightningModule.load_from_checkpoint(checkpoint, map_location='cpu').eval()
    torch.testing.assert_close(restored(inputs), logits.detach())

    output_path = tmp_path / 'classifier.onnx'
    export_model(restored, output_path, sample_length=512)
    session = onnxruntime.InferenceSession(str(output_path), providers=['CPUExecutionProvider'])
    for waveform in (inputs, torch.randn(1, 768)):
        actual = session.run(['logits'], {'input_values': waveform.numpy()})[0]
        torch.testing.assert_close(torch.from_numpy(actual), restored(waveform), rtol=1e-4, atol=1e-5)
