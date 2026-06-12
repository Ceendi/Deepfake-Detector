import os
import torch
import torch.nn as nn
import lightning.pytorch as pl
from lightning.pytorch.callbacks import ModelCheckpoint, TQDMProgressBar, EarlyStopping
from transformers import Wav2Vec2ForSequenceClassification
from sklearn.metrics import roc_curve
import numpy as np
try:
    from datasets import ASVspoofDataModule
except ImportError:
    from .datasets import ASVspoofDataModule

# Aktywacja rdzeni Tensor (Tensor Cores) dla serii RTX 40
torch.set_float32_matmul_precision('high')


def compute_eer(y_true, y_score):
    fpr, tpr, thresholds = roc_curve(y_true, y_score)
    fnr = 1 - tpr
    idx = np.nanargmin(np.absolute((fnr - fpr)))
    eer = fpr[idx]
    return eer

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
        
        self.validation_step_outputs = []

    def forward(self, input_values):
        # Normalize input (Zero-Mean, Unit-Variance) per sample
        # input_values shape: (B, Time)
        mean = input_values.mean(dim=-1, keepdim=True)
        std = input_values.std(dim=-1, keepdim=True)
        input_values = (input_values - mean) / (std + 1e-7)
        
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
        
        self.validation_step_outputs.append((preds.detach().cpu(), y.detach().cpu()))
        
        self.log('val_loss', loss, on_epoch=True, prog_bar=True, logger=True)
        return loss
        
    def on_validation_epoch_end(self):
        all_preds = torch.cat([out[0] for out in self.validation_step_outputs]).float().numpy()
        all_targets = torch.cat([out[1] for out in self.validation_step_outputs]).float().numpy()
        
        if len(np.unique(all_targets)) > 1:
            eer = compute_eer(all_targets, all_preds)
            self.log('val_eer', eer, prog_bar=True, logger=True)
            
            acc = ((all_preds > 0.5) == all_targets).mean()
            self.log('val_acc', acc, prog_bar=True, logger=True)
            
        self.validation_step_outputs.clear()

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

        scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(
            optimizer,
            mode='min',
            factor=0.5,
            patience=3,
            min_lr=1e-7,
        )

        return {
            "optimizer": optimizer,
            "lr_scheduler": {
                "scheduler": scheduler,
                "monitor": "val_eer",
                "interval": "epoch",
                "frequency": 1,
            },
        }

class InfoCallback(pl.Callback):
    def on_train_epoch_start(self, trainer, pl_module):
        epoch = trainer.current_epoch + 1
        max_epochs = trainer.max_epochs
        print(f"\n🚀 Rozpoczynanie epoki {epoch}/{max_epochs}...")

    def on_validation_end(self, trainer, pl_module):
        if trainer.sanity_checking:
            return
        epoch = trainer.current_epoch + 1
        max_epochs = trainer.max_epochs
        print(f"\n\n{'='*60}")
        print("✅ Zakończono walidację (Epoka {}/{})!".format(epoch, max_epochs))
        print("💾 Checkpointy zostały zaktualizowane (o ile wynik był lepszy).")
        print("🛑 To jest w pełni BEZPIECZNY MOMENT, aby przerwać trening (Ctrl+C).")
        print(f"{'='*60}\n")

if __name__ == "__main__":
    if not torch.cuda.is_available():
        print("BŁĄD: Karta graficzna (GPU) nie została wykryta! Trening modelu Wav2Vec2 wymaga GPU.")
        print("Przerwanie działania skryptu.")
        exit(1)

    SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
    DATA_ROOT = os.path.join(SCRIPT_DIR, "data/archive/LA/LA")
    EXTRA_FAKE_DIR = os.path.join(SCRIPT_DIR, "data/generated_audio")
    CHECKPOINT_DIR = os.path.join(SCRIPT_DIR, "checkpoints", "w2v2")
    IN_THE_WILD_DIR = os.path.join(SCRIPT_DIR, "data/release_in_the_wild/release_in_the_wild")
    os.makedirs(CHECKPOINT_DIR, exist_ok=True)

    data_module = ASVspoofDataModule(
        data_root=DATA_ROOT,
        extra_fake_dir=EXTRA_FAKE_DIR,
        in_the_wild_dir=IN_THE_WILD_DIR,
        batch_size=12,
        num_workers=8,
        fake_to_real_ratio=1.0,  # 1:1 REAL:FAKE
    )

    model = Wav2Vec2LightningModule(
        model_name="facebook/wav2vec2-xls-r-300m",
        lr_transformer=1e-5,
        lr_head=1e-3
    )

    checkpoint_callback = ModelCheckpoint(
        dirpath=CHECKPOINT_DIR,
        filename='w2v2_model-{epoch:02d}-{val_eer:.4f}',
        monitor='val_eer',
        mode='min',
        save_top_k=3,
        save_last=True
    )

    progress_bar = TQDMProgressBar(refresh_rate=10)
    info_callback = InfoCallback()

    early_stop_callback = EarlyStopping(
        monitor='val_eer',
        mode='min',
        patience=30,  # Zwiększono z 7 na 30, by powstrzymać przedwczesne ubijanie treningu!
        min_delta=0.001,
        verbose=True,
    )

    trainer = pl.Trainer(
        max_epochs=30,
        val_check_interval=0.5,
        callbacks=[checkpoint_callback, early_stop_callback, progress_bar, info_callback],
        accelerator='auto',
        devices=1,
        precision="bf16-mixed",                                                                 
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
