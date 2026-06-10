import os
import torch
import torch.nn as nn
import lightning.pytorch as pl
from lightning.pytorch.callbacks import ModelCheckpoint, TQDMProgressBar
from transformers import Wav2Vec2ForSequenceClassification
from datasets import ASVspoofDataModule
class Wav2Vec2LightningModule(pl.LightningModule):
    def __init__(self, model_name="facebook/wav2vec2-xls-r-300m", lr_transformer=1e-5, lr_head=1e-3):
        super().__init__()
        self.save_hyperparameters()
        self.lr_transformer = lr_transformer
        self.lr_head = lr_head
        self.model = Wav2Vec2ForSequenceClassification.from_pretrained(
            model_name,
            num_labels=1,
            problem_type="multi_label_classification"                            
        )
        self.model.freeze_feature_extractor()
    def forward(self, input_values):
        return self.model(input_values).logits
    def training_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(-1)
        loss = nn.functional.binary_cross_entropy_with_logits(logits, y)
        self.log('train_loss', loss, on_step=True, on_epoch=True, prog_bar=True, logger=True)
        return loss
    def validation_step(self, batch, batch_idx):
        x, y = batch
        logits = self(x).squeeze(-1)
        loss = nn.functional.binary_cross_entropy_with_logits(logits, y)
        preds = torch.sigmoid(logits)
        acc = ((preds > 0.5).float() == y).float().mean()
        self.log('val_loss', loss, on_epoch=True, prog_bar=True, logger=True)
        self.log('val_acc', acc, on_epoch=True, prog_bar=True, logger=True)
        return loss
    def configure_optimizers(self):
        transformer_params = []
        head_params = []
        for name, param in self.model.named_parameters():
            if not param.requires_grad:
                continue
            if "classifier" in name or "projector" in name:
                head_params.append(param)
            else:
                transformer_params.append(param)
        optimizer = torch.optim.AdamW([
            {'params': transformer_params, 'lr': self.lr_transformer},
            {'params': head_params, 'lr': self.lr_head}
        ])
        return optimizer
if __name__ == "__main__":
    if not torch.cuda.is_available():
        print("BŁĄD: Karta graficzna (GPU) nie została wykryta! Trening modelu Wav2Vec2 wymaga GPU.")
        print("Przerwanie działania skryptu.")
        exit(1)
    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    DATA_ROOT = os.path.join(SCRIPT_DIR, "data/archive/LA/LA")
    CHECKPOINT_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "w2v2")
    os.makedirs(CHECKPOINT_DIR, exist_ok=True)
    data_module = ASVspoofDataModule(
        data_root=DATA_ROOT,
        batch_size=8,
        num_workers=4
    )
    model = Wav2Vec2LightningModule(
        model_name="facebook/wav2vec2-xls-r-300m",
        lr_transformer=1e-5,
        lr_head=1e-3
    )
    checkpoint_callback = ModelCheckpoint(
        dirpath=CHECKPOINT_DIR,
        filename='w2v2_model-{epoch:02d}-{val_loss:.2f}',
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
        precision="16-mixed",                                                                 
        log_every_n_steps=10
    )
    import glob
    ckpt_path = None
    last_ckpt = os.path.join(CHECKPOINT_DIR, "last.ckpt")
    if os.path.exists(last_ckpt):
        ckpt_path = last_ckpt
    else:
        ckpts = glob.glob(os.path.join(CHECKPOINT_DIR, "*.ckpt"))
        if ckpts:
            ckpt_path = max(ckpts, key=os.path.getmtime)
    if ckpt_path:
        print(f"Wznawianie treningu z checkpointu: {ckpt_path}...")
    else:
        print("Rozpoczynanie treningu modelu Wav2Vec2-XLS-R 300M od zera...")
    trainer.fit(model, datamodule=data_module, ckpt_path=ckpt_path)
    print("Trening zakończony!")
