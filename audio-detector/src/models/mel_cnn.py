"""Mel-spectrogram ResNet+GRU classifier shared by training and inference (no Lightning)."""

import torch
import torch.nn.functional as F
import torchaudio
from torch import nn
from torchvision import models


class MelResNetGRU(nn.Module):
    def __init__(self):
        super().__init__()
        self.resnet = models.resnet18(weights=None)
        self.resnet.conv1 = nn.Conv2d(1, 64, kernel_size=7, stride=2, padding=3, bias=False)
        self.gru = nn.GRU(input_size=512, hidden_size=64, bidirectional=True, batch_first=True)
        self.dropout = nn.Dropout(0.3)
        self.classifier = nn.Linear(128, 1)

    def forward(self, x):
        x = self.resnet.conv1(x)
        x = self.resnet.bn1(x)
        x = self.resnet.relu(x)
        x = self.resnet.maxpool(x)
        x = self.resnet.layer1(x)
        x = self.resnet.layer2(x)
        x = self.resnet.layer3(x)
        x = self.resnet.layer4(x)
        x = F.adaptive_avg_pool2d(x, (1, None))
        x = x.squeeze(2)
        x = x.permute(0, 2, 1)
        x, _ = self.gru(x)
        x = x.mean(dim=1)
        x = self.dropout(x)
        x = self.classifier(x)
        return x


class MelCNN(nn.Module):
    """Waveform (16 kHz) -> log-mel -> logit.

    Attribute names are part of the checkpoint format: they match the state_dict keys
    saved by training.train_mel.MelCNNLightningModule, which subclasses this module.
    """

    def __init__(self):
        super().__init__()
        self.model = MelResNetGRU()
        self.mel_transform = torchaudio.transforms.MelSpectrogram(
            sample_rate=16000, n_fft=2048, hop_length=512, n_mels=128
        )
        self.amp_to_db = torchaudio.transforms.AmplitudeToDB()

    def forward(self, x):
        if x.dim() == 1:
            x = x.unsqueeze(0)
        mean = x.mean(dim=-1, keepdim=True)
        std = x.std(dim=-1, keepdim=True)
        x = (x - mean) / (std + 1e-7)
        mel = self.mel_transform(x)
        mel_db = self.amp_to_db(mel)
        mel_db = mel_db.unsqueeze(1)
        return self.model(mel_db)


def load_mel_cnn(checkpoint_path):
    """Load MelCNN weights from a Lightning .ckpt without importing Lightning."""
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    model = MelCNN()
    # strict: a renamed attribute must fail loudly instead of leaving random weights.
    model.load_state_dict(checkpoint["state_dict"], strict=True)
    return model.eval()
