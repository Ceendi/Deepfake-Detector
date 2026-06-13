# Video Detector — training pipeline

Trening modelu detekcji deepfake video (Osoba 3). Trening odbywa się **poza obrazem
produkcyjnym** (Colab Pro T4 / Kaggle GPU) — kontener robi wyłącznie inferencję CPU
na wyeksportowanym ONNX. Dlatego zależności treningowe są w `requirements.txt`,
a nie w `pyproject.toml` serwisu.

## Architektura modelu

```
wejście   (B, T=16, 3, 224, 224)        16 wyrównanych cropów twarzy z klipu
   │
   ▼  flatten (B·T, 3, 224, 224)
EfficientNet-B4  (timm, ImageNet pretrained, num_classes=0, global avg pool)
   │  (B·T, 1792)  →  reshape (B, T, 1792)
   ▼
Bi-LSTM  hidden=128, bidirectional       (B, T, 256)
   ▼
Attention pooling  (Linear 256→64 → Tanh → Linear 64→1 → softmax po T)
   │  (B, 256) — uczona suma ważona klatek
   ▼
Head: Dropout(0.3) → Linear(256, 64) → ReLU → Linear(64, 1)
   ▼
logit → sigmoid = prob_fake
```

| Komponent | Parametry | Rola |
|---|---|---|
| EfficientNet-B4 | ~17.6 M | cechy przestrzenne per klatka (artefakty blendingu, tekstura skóry, granice twarzy) |
| Bi-LSTM 128 | ~2.0 M | spójność temporalna (migotanie, niestabilność tożsamości między klatkami) |
| Attention pool | ~16 K | ważenie klatek — zamiast średniej model skupia się na najbardziej podejrzanych; wagi wskazują top-3 klatki pod Grad-CAM |
| Head MLP | ~16 K | klasyfikacja binarna |
| **Razem** | **~19.6 M** | mieści się w fp16 na T4 (15 GB) |

Dlaczego tak (a nie inaczej):

- **CNN + RNN to wymaganie formalne projektu.** TimeSformer / Video Swin odpadają
  (za duże na T4), 3D-CNN ma gorszy stosunek jakości do kosztu przy 16 klatkach.
- **B4 to sweet spot**: B0–B2 za słabe na subtelne artefakty, B5+ nie zmieści się
  z 128 klatkami w batchu na T4 nawet w fp16.
- **224×224 zamiast natywnego 380** dla B4: 16 klatek × batch 8 = 128 obrazów na
  krok; przy 380 px koszt rośnie ~2.9×. Crop twarzy (nie cała klatka) zachowuje
  gęstość detali mimo mniejszej rozdzielczości.
- **Attention pooling zamiast last-hidden-state**: manipulacja nie musi być widoczna
  na każdej klatce; pooling uczony pozwala modelowi zignorować klatki "czyste".
- **Rozdzielenie `backbone` / `temporal`** w `VideoDeepfakeNet` jest celowe — export
  ONNX produkuje dwa grafy: per-klatkowy CNN (micro-batching klatek z różnych analiz)
  i część temporalną z dynamiczną osią T (early-stop inferencji po 8 klatkach przy
  confidence > 0.95).

## Setup treningu

| Element | Wartość | Uzasadnienie |
|---|---|---|
| Loss | BCEWithLogits + label smoothing 0.1 | etykiety FF++ bywają "miękkie" (kompresja zaciera artefakty); smoothing tylko w train, val na czystym BCE |
| Optymalizator | AdamW, weight decay 1e-2 | |
| Discriminative LR | **backbone 5e-4, LSTM+head 3e-4** (`grid_search.py`) | brief zakładał 1e-5/1e-3, ale grid search 5×2 (10 prób × 6 epok, pełny cache FF++, `grid_results.csv`) pokazał monotoniczny wzrost AUC z LR backbone'u: 1e-5→0.74, 5e-5→0.87, 2e-4→0.88, **5e-4→0.955**, 1e-3→0.72 (kolaps). Przy 1e-5 backbone de facto stoi w miejscu i model jest trwale niedouczony. Niższy LR głowy (3e-4) wygrywa z 1e-3 w każdym wierszu — mniejszy rozjazd temp uczenia stabilizuje trening. Zwycięzca na teście: AUC 0.952, F1 0.931, EER 10.7% — cele intra-dataset briefu osiągnięte |
| Scheduler | OneCycleLR per-step, pct_start 0.1 | |
| Precision | fp16 mixed | bez tego OOM na T4 |
| Batch | 8 × grad accumulation 2 (efektywnie 16) | przy OOM: `--batch-size 4 --accumulate 4 --grad-checkpointing` |
| Epoki | max 20, early stopping na `val_auc` (patience 7, `--monitor`) | brief mowi o val F1, ale F1 przy progu 0.5 szczytuje sztucznie w epoce ~2 (niedotrenowany model daje logity ~0 z biasem w strone FAKE → wysoki recall winduje F1) i ucina trening dokladnie wtedy, gdy val_loss/AUC zaczynaja sie poprawiac — zaobserwowane empirycznie na subset 600 klipow. AUC jest niezalezne od progu; `--monitor val_f1` przywraca zachowanie z briefu |
| Augmentacje | HorizontalFlip, RandomBrightnessContrast, **ImageCompression q40–90**, GaussNoise, CoarseDropout | ImageCompression jest krytyczny dla generalizacji cross-dataset (Celeb-DF); wszystkie przez `ReplayCompose` — te same parametry na każdej klatce klipu, żeby nie wstrzykiwać sztucznego migotania, które LSTM odczytałby jako sygnał |

## Dane: FaceForensics++ → cache cropów

Dekodowanie video + RetinaFace per epokę jest o rzędy wielkości za wolne, więc
preprocessing robimy raz (`prep_cache.py`), a trening czyta gotowe JPEG-i 224×224
(analogicznie do cache'u Parquet w audio-detectorze; dla video Parquet z pikselami
nie ma sensu — augmentacja ImageCompression musi działać na obrazach).

Pipeline preprocessing: uniform sampling 16 klatek → RetinaFace (InsightFace
`buffalo_l`, tylko moduł detection) → największa twarz → 5-landmark alignment
(`norm_crop`) → 224×224 JPEG q95 → `manifest.csv`.

**Anty-leakage:** podział wg oficjalnych splitów FF++ (`train/val/test.json`);
fake `{a}_{b}.mp4` trafia do splitu tylko gdy **oba** id źródłowe są w tym samym
splicie. Bez tego tożsamości z train wyciekają do val/test i F1 jest zawyżone.

Subset wg briefu: train 1000+1000, val 200+200, test 300+300; fake'i round-robin
z Deepfakes / Face2Face / FaceSwap (żaden attack type nie dominuje).

## Komendy

```bash
pip install -r requirements.txt

# 1. Jednorazowy cache (host / Colab). Oficjalne splity FF++ sa w training/splits/
#    (commitowane). Obslugiwany jest uklad oficjalny (original_sequences/...) oraz
#    splaszczony z Kaggle (original/, Deepfakes/, ...) — wykrywany automatycznie.
python prep_cache.py --ffpp-root data/FaceForensics++_C23 --out data/ffpp_cache

# 2. Trening (T4)
python train.py --cache-dir data/ffpp_cache --wandb deepfake-detector

# 2a. Wznowienie przerwanego treningu (checkpoint last.ckpt zapisywany po kazdej epoce;
#     przywraca wagi + optimizer + scheduler + epoke). Te same --epochs co w przerwanym
#     runie — OneCycleLR ma harmonogram policzony na caly trening.
python train.py --cache-dir data/ffpp_cache --resume

# 3. Ewaluacja (precision/recall/F1/AUC/EER + ploty) — lustrzane odbicie
#    audio-detector/eval.py, te same metryki i nazwy plikow wykresow
python eval.py --model checkpoints/effnet_lstm/last.ckpt --dataset data/ffpp_cache

# 4. Export ONNX (backbone.onnx + temporal.onnx + sanity check vs PyTorch)
python export_onnx.py --checkpoint checkpoints/effnet_lstm/last.ckpt
```

## TODO (kolejne tygodnie wg briefu)

- [ ] Cross-dataset eval na Celeb-DF v2 (target accuracy ≥ 0.75) — `prep_cache.py`
      wymaga wariantu dla struktury katalogów Celeb-DF
- [x] Grad-CAM offline: `gradcam.py` (hooki na `backbone.bn2` + wagi atencji,
      panele PNG top-3 klatek). Checkpoint .ckpt zostaje w produkcji obok ONNX
      wyłącznie do heatmap. **Domyślna metoda to HiResCAM (grad⊙act), nie
      Grad-CAM++**: B4 ma w narożnikach mapy 7×7 kanały-"biasy" o ogromnej
      amplitudzie (artefakt zero-paddingu) — CAM++ waży całe kanały sumą
      gradientów po przestrzeni, przez co róg świecił w 100% klatek niezależnie
      od wejścia, mimo że test okluzji (zasłonięcie rogu: Δprob ≈ +0.01;
      zasłonięcie twarzy na fake'ach: Δprob ≈ −0.30) dowodzi zerowego wpływu
      rogu na decyzję. grad⊙act gasi komórki o dużej aktywacji i zerowym
      gradiencie: argmax-w-rogu spadł ze 100% do 13% (losowo ~6%).
      `--method gradcampp` przywraca klasyczny wariant
- [x] Grad-CAM integracja produkcyjna (`src/inference.py` + `src/consumer.py`:
      HiResCAM na top-3 klatkach wg atencji → PNG → S3 `analysis-artifacts` →
      `gradcam_keys` w `analysis.results`; gradienty per klatka liczone VJP
      z seedem d(logit)/d(feat) — wynik identyczny z pelnym backwardem)
- [ ] Benchmark frame sampling uniform vs adaptive (raport MO)
- [ ] Per-attack-type heatmap (FaceSwap vs Deepfakes vs Face2Face) — manifest ma
      kolumnę `method`, wystarczy filtrować test split
