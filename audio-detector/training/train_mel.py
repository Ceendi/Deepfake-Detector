import os
import torch
import torch.nn as nn
import torch.nn.functional as F
from torchvision import models
import lightning.pytorch as pl
from lightning.pytorch.callbacks import ModelCheckpoint, TQDMProgressBar
try:
    from datasets import ParquetASVspoofDataModule
except ImportError:
    from .datasets import ParquetASVspoofDataModule
class MelResNetGRU(nn.Module):
    def __init__(self):
        super().__init__()
        self.resnet = models.resnet18(weights=None)
        self.resnet.conv1 = nn.Conv2d(1, 64, kernel_size=7, stride=2, padding=3, bias=False)
        self.resnet.fc = nn.Identity()
        self.gru = nn.GRU(
            input_size=512, 
            hidden_size=64, 
            bidirectional=True, 
            batch_first=True
        )
        self.classifier = nn.Linear(128, 1)
    def forward(self, x):
        x = self.resnet(x)                  
        x = x.unsqueeze(1)                     
        x, _ = self.gru(x)                     
        x = x.squeeze(1)                  
        x = self.classifier(x)                
        return x
class MelCNNLightningModule(pl.LightningModule):
    def __init__(self, learning_rate=1e-3):
        super().__init__()
        self.save_hyperparameters()
        self.model = MelResNetGRU()
        self.learning_rate = learning_rate
    def forward(self, x):
        return self.model(x)
    def training_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(1)
        loss = F.binary_cross_entropy_with_logits(logits, y)
        self.log('train_loss', loss, on_step=True, on_epoch=True, prog_bar=True, logger=True)
        return loss
    def validation_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(1)
        loss = F.binary_cross_entropy_with_logits(logits, y)
        preds = torch.sigmoid(logits)
        acc = ((preds > 0.5).float() == y).float().mean()
        self.log('val_loss', loss, on_epoch=True, prog_bar=True, logger=True)
        self.log('val_acc', acc, on_epoch=True, prog_bar=True, logger=True)
        return loss
    def configure_optimizers(self):
        optimizer = torch.optim.AdamW(self.parameters(), lr=self.learning_rate)
        return optimizer
if __name__ == "__main__":
    if not torch.cuda.is_available():
        print("BŁĄD: Karta graficzna (GPU) nie została wykryta! Trening modelu Mel-CNN wymaga GPU.")
        print("Przerwanie działania skryptu.")
        exit(1)
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    CACHE_DIR = os.path.join(SCRIPT_DIR, "data", "data_cache")
    CHECKPOINT_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "mel_resnet")
    os.makedirs(CHECKPOINT_DIR, exist_ok=True)
    data_module = ParquetASVspoofDataModule(
        cache_dir=CACHE_DIR,
        batch_size=16,
        num_workers=4
    )
    model = MelCNNLightningModule(learning_rate=1e-3)
    checkpoint_callback = ModelCheckpoint(
        dirpath=CHECKPOINT_DIR,
        filename='mel_model-{epoch:02d}-{val_loss:.2f}',
        monitor='val_loss',                                                    
        mode='min',
        save_top_k=3,                                
        save_last=True                                                                
    )
    progress_bar = TQDMProgressBar(refresh_rate=10)
    trainer = pl.Trainer(
        max_epochs=20,                             
        callbacks=[checkpoint_callback, progress_bar],
        accelerator='auto',                                      
        devices=1,
        log_every_n_steps=10
    )
    print("Rozpoczynanie treningu modelu Mel-CNN+GRU...")
    trainer.fit(model, datamodule=data_module)
    print("Trening zakończony!")
