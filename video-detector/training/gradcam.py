"""Grad-CAM++ dla modelu video: heatmapy obszarow, ktore wplynely na decyzje.

Dziala na checkpointzie PyTorch (.ckpt) — ONNX nie ma grafu gradientow, dlatego
produkcja trzyma .ckpt obok .onnx wylacznie do heatmap.

Przeplyw:
  1. forward calego klipu (1, 16, 3, 224, 224) -> jeden logit prob_fake
  2. backward() -> gradienty plyna przez head + attention + Bi-LSTM do map
     aktywacji ostatniej warstwy conv backbone'u (bn2 po conv_head, 7x7x1792
     per klatka, po aktywacji SiLU)
  3. Grad-CAM++ na zlapanych (aktywacje, gradienty) -> mapa 7x7 per klatka
     -> upsample do 224x224
  4. wybor top-K klatek wg wag attention poolingu (te, na ktore model
     najmocniej "patrzyl") -> overlay JET na cropie -> panel PNG

Implementacja reczna zamiast pytorch-grad-cam: biblioteka zaklada wejscie
(B, 3, H, W) klasyfikatora obrazow, a my mamy sekwencje (B, T, 3, H, W)
z czescia temporalna za CNN.

UWAGA interpretacyjna: heatmapa pokazuje, gdzie model patrzyl, a nie
ground-truth manipulacji. Jakosc lokalizacji rosnie z jakoscia modelu.

    python gradcam.py --checkpoint checkpoints/effnet_lstm/last.ckpt \
        --cache-dir data/ffpp_cache --num-fake 2 --num-real 2
"""

import argparse
import os

import cv2
import numpy as np
import pandas as pd
import torch
import torch.nn.functional as F

try:
    from datasets import IMAGENET_MEAN, IMAGENET_STD, FFPPClipDataset
    from model import VideoLightningModule
except ImportError:
    from .datasets import IMAGENET_MEAN, IMAGENET_STD, FFPPClipDataset
    from .model import VideoLightningModule

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


class GradCamPP:
    """Hooki na warstwe conv + wagi attention; liczy Grad-CAM++ per klatka."""

    def __init__(self, net, method: str = "hirescam"):
        self.net = net
        self.method = method
        self.activations = None
        self.gradients = None
        self.attention = None
        # bn2 = BatchNormAct2d po conv_head — ostatnie *aktywowane* mapy przestrzenne
        net.backbone.bn2.register_forward_hook(self._save_activation)
        net.backbone.bn2.register_full_backward_hook(self._save_gradient)
        net.temporal.pool.register_forward_hook(self._save_attention)

    def _save_activation(self, module, args, output):
        self.activations = output.detach()

    def _save_gradient(self, module, grad_input, grad_output):
        self.gradients = grad_output[0].detach()

    def _save_attention(self, module, args, output):
        self.attention = output[1].detach()  # (B, T)

    def run(self, clip: torch.Tensor):
        """clip: (T, 3, H, W) znormalizowany. -> (prob_fake, cams (T,H,W), attn (T,))"""
        # cuDNN wymaga trybu train do backwardu RNN; nasz LSTM nie ma dropoutu,
        # wiec train() nie zmienia wyniku forwardu (BN/Dropout reszty zostaja w eval)
        self.net.temporal.lstm.train()
        self.net.zero_grad(set_to_none=True)
        logit = self.net(clip.unsqueeze(0))[0, 0]
        logit.backward()

        acts, grads = self.activations, self.gradients  # (T, C, h, w)
        if self.method == "hirescam":
            # grad (x) act element-wise: gasi komorki o duzej aktywacji ale zerowym
            # wplywie na logit. B4 ma w narozach mapy kanaly-"biasy" o ogromnej
            # amplitudzie (artefakt zero-paddingu) — klasyczny CAM/CAM++ wazy caly
            # kanal suma gradientow po przestrzeni, wiec rog swieci mimo ze okluzja
            # pokazuje zerowy wplyw na decyzje (patrz README, sekcja Grad-CAM)
            cam = F.relu((grads * acts).sum(dim=1))                          # (T, h, w)
        else:  # gradcampp
            alpha_num = grads.pow(2)
            alpha_den = 2 * grads.pow(2) + (acts * grads.pow(3)).sum(dim=(2, 3), keepdim=True)
            alpha = alpha_num / (alpha_den + 1e-8)
            weights = (alpha * F.relu(grads)).sum(dim=(2, 3))                # (T, C)
            cam = F.relu((weights[..., None, None] * acts).sum(dim=1))       # (T, h, w)

        cam = F.interpolate(cam.unsqueeze(1), size=clip.shape[-2:],
                            mode="bilinear", align_corners=False).squeeze(1)  # (T, H, W)
        # surowe wartosci przed normalizacja — do diagnostyki (skala = sila dowodu;
        # normalizacja per-klatka rozciaga szum slabych klatek do pelnej skali)
        self.last_raw = cam.detach().cpu().numpy()
        flat = cam.flatten(1)
        lo = flat.min(dim=1).values[:, None, None]
        hi = flat.max(dim=1).values[:, None, None]
        cam = (cam - lo) / (hi - lo + 1e-8)

        prob = torch.sigmoid(logit).item()
        return prob, cam.cpu().numpy(), self.attention[0].cpu().numpy()


def denormalize(clip: torch.Tensor) -> np.ndarray:
    """(T, 3, H, W) znormalizowany -> (T, H, W, 3) uint8 RGB."""
    img = clip.permute(0, 2, 3, 1).cpu().numpy()
    img = (img * IMAGENET_STD + IMAGENET_MEAN) * 255.0
    return np.clip(img, 0, 255).astype(np.uint8)


def render_panel(frames_rgb, cams, attn, top_idx, header: str) -> np.ndarray:
    """Panel BGR: wiersz oryginalow + wiersz overlay'ow dla top-K klatek."""
    origs, overlays = [], []
    for t in top_idx:
        orig = cv2.cvtColor(frames_rgb[t], cv2.COLOR_RGB2BGR)
        heat = cv2.applyColorMap((cams[t] * 255).astype(np.uint8), cv2.COLORMAP_JET)
        over = cv2.addWeighted(orig, 0.55, heat, 0.45, 0)
        cv2.putText(over, f"t={t} attn={attn[t]:.2f}", (6, 20),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 255), 2)
        origs.append(orig)
        overlays.append(over)
    panel = np.vstack([np.hstack(origs), np.hstack(overlays)])
    bar = np.full((34, panel.shape[1], 3), 245, dtype=np.uint8)
    cv2.putText(bar, header, (6, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (20, 20, 20), 2)
    return np.vstack([bar, panel])


def main():
    parser = argparse.ArgumentParser(description="Grad-CAM++ heatmaps for the video model")
    parser.add_argument("--checkpoint",
                        default=os.path.join(SCRIPT_DIR, "checkpoints", "effnet_lstm", "last.ckpt"))
    parser.add_argument("--cache-dir", default=os.path.join(SCRIPT_DIR, "data", "ffpp_cache"))
    parser.add_argument("--out-dir", default=os.path.join(SCRIPT_DIR, "gradcam_out"))
    parser.add_argument("--split", default="test", choices=["train", "val", "test"])
    parser.add_argument("--num-fake", type=int, default=2)
    parser.add_argument("--num-real", type=int, default=2)
    parser.add_argument("--clip", default=None,
                        help="Konkretny clip_dir z manifestu zamiast losowania")
    parser.add_argument("--method", default="hirescam", choices=["hirescam", "gradcampp"],
                        help="hirescam (grad*act, domyslne — odporne na artefakt naroznikow B4) "
                             "lub klasyczny gradcampp")
    parser.add_argument("--topk", type=int, default=3, help="Ile klatek wg wag atencji")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)
    # grad_checkpointing=False: rekompute w backward psuje kolejnosc hookow
    module = VideoLightningModule.load_from_checkpoint(
        args.checkpoint, map_location=args.device, pretrained=False, grad_checkpointing=False,
    )
    module.eval()
    net = module.model
    cam_engine = GradCamPP(net, method=args.method)

    manifest = pd.read_csv(os.path.join(args.cache_dir, "manifest.csv"))
    if args.clip:
        rows = manifest[manifest["clip_dir"].str.replace("\\", "/")
                        == args.clip.replace("\\", "/")]
    else:
        part = manifest[manifest["split"] == args.split]
        rows = pd.concat([
            part[part["label"] == 1].sample(args.num_fake, random_state=args.seed),
            part[part["label"] == 0].sample(args.num_real, random_state=args.seed),
        ])
    if rows.empty:
        print("BLAD: nie znaleziono klipow do wizualizacji")
        raise SystemExit(1)

    dataset = FFPPClipDataset(rows, args.cache_dir, is_train=False)
    for i in range(len(dataset)):
        clip, label = dataset[i]
        row = rows.iloc[i]
        prob, cams, attn = cam_engine.run(clip.to(args.device))
        top_idx = np.argsort(attn)[::-1][: args.topk]

        truth = "FAKE" if int(row["label"]) == 1 else "REAL"
        header = (f"{row['method']}/{row['video_id']}  truth={truth}  "
                  f"prob_fake={prob:.3f}  top-{args.topk} attn frames")
        panel = render_panel(denormalize(clip), cams, attn, top_idx, header)

        out_path = os.path.join(args.out_dir, f"{row['method']}_{row['video_id']}.png")
        cv2.imwrite(out_path, panel)
        print(f"{out_path}  truth={truth}  prob_fake={prob:.3f}")

    print(f"\nGotowe — panele w {args.out_dir}")


if __name__ == "__main__":
    main()
