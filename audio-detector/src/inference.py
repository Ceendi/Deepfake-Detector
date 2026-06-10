import os
import subprocess
import torch
import torchaudio
import numpy as np
import soundfile as sf
import onnxruntime as ort
import matplotlib.pyplot as plt
import math
import time
from prometheus_client import Histogram, Gauge
import sys
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
sys.path.append(PROJECT_ROOT)
from training.train_mel import MelCNNLightningModule  # noqa: E402
W2V2_ONNX_PATH = os.path.join(PROJECT_ROOT, "training", "checkpoints", "w2v2", "w2v2.onnx")
# Mini-batch size for the chunk loop; bounds peak memory on long clips. Override via env.
_BATCH_SIZE = int(os.getenv("INFERENCE_BATCH_SIZE", "32"))
MEL_CKPT_PATH = os.path.join(PROJECT_ROOT, "training", "checkpoints", "mel_resnet", "last.ckpt")

INFERENCE_TIME = Histogram(
    "audio_inference_duration_seconds",
    "Time taken to run inference on an audio file",
    buckets=[0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 15.0, 20.0, 30.0]
)
MODEL_LOAD_TIME = Gauge(
    "audio_model_load_duration_seconds",
    "Time taken to load the audio models into memory"
)
class AudioInference:
    def __init__(self):
        start_load = time.time()
        self.w2v2_session = ort.InferenceSession(W2V2_ONNX_PATH, providers=['CPUExecutionProvider'])
        self.mel_module = MelCNNLightningModule.load_from_checkpoint(MEL_CKPT_PATH, map_location=torch.device('cpu'))
        self.mel_model = self.mel_module.model
        self.mel_model.eval()
        self.mel_transform = torchaudio.transforms.MelSpectrogram(
            sample_rate=22050,
            n_fft=2048,
            hop_length=512,
            n_mels=128
        )
        self.db_transform = torchaudio.transforms.AmplitudeToDB()
        MODEL_LOAD_TIME.set(time.time() - start_load)
    def _extract_audio(self, input_path, sr, out_path):
        subprocess.run([
            "ffmpeg", "-y", "-i", input_path,
            "-ar", str(sr), "-ac", "1", out_path
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def _load_wav(self, path):
        # torchaudio 2.11 routes load() through torchcodec (not bundled); soundfile is already a
        # dependency and backend-independent. ffmpeg wrote mono wavs, so take channel 0.
        data, _ = sf.read(path, dtype="float32", always_2d=True)
        return torch.from_numpy(data[:, 0])
    def generate_gradcam(self, audio_chunk_22k, out_png_path):
        self.mel_model.zero_grad()
        activations = []
        gradients = []
        def fwd_hook(module, input, output):
            activations.append(output)
        def bwd_hook(module, grad_in, grad_out):
            gradients.append(grad_out[0])
        target_layer = self.mel_model.resnet.layer4
        h1 = target_layer.register_forward_hook(fwd_hook)
        h2 = target_layer.register_full_backward_hook(bwd_hook)
        mel_spec = self.mel_transform(audio_chunk_22k)
        mel_spec = self.db_transform(mel_spec)
        mel_in = mel_spec.unsqueeze(0).unsqueeze(0)                               
        mel_in.requires_grad_(True)
        logits = self.mel_model(mel_in)
        logits.backward()
        h1.remove()
        h2.remove()
        if not activations or not gradients:
            plt.imsave(out_png_path, mel_spec.squeeze().detach().numpy(), cmap='viridis')
            return out_png_path
        acts = activations[0].squeeze()
        grads = gradients[0].squeeze()
        weights = torch.mean(grads, dim=(1, 2))
        cam = torch.zeros(acts.shape[1:], dtype=torch.float32)
        for i, w in enumerate(weights):
            cam += w * acts[i]
        cam = torch.relu(cam)
        cam = cam.detach().numpy()
        cam = cam - np.min(cam)
        cam = cam / (np.max(cam) + 1e-8)
        cam_tensor = torch.tensor(cam).unsqueeze(0).unsqueeze(0)
        cam_resized = torch.nn.functional.interpolate(
            cam_tensor, 
            size=(mel_spec.shape[0], mel_spec.shape[1]), 
            mode='bilinear', 
            align_corners=False
        )
        cam = cam_resized.squeeze().numpy()
        plt.figure(figsize=(10, 4))
        plt.imshow(mel_spec.squeeze().detach().numpy(), aspect='auto', origin='lower', cmap='viridis')
        plt.imshow(cam, aspect='auto', origin='lower', cmap='jet', alpha=0.5)
        plt.axis('off')
        plt.tight_layout()
        plt.savefig(out_png_path, bbox_inches='tight', pad_inches=0)
        plt.close()
        return out_png_path
    def _generate_insights(self, segment_predictions, overall_prob):
        insights = []
        if not segment_predictions:
            return ["Brak danych do analizy odcinkowej."]
        if overall_prob > 0.85:
            insights.append("Całe nagranie wykazuje spójne, bardzo wysokie parametry syntezy AI - model wskazuje na mocną ingerencję lub całkowite wygenerowanie głosu.")
        elif overall_prob < 0.15:
            insights.append("Sygnał audio jest w pełni naturalny, nie wykryto żadnych śladów modulacji AI.")
        fake_segments = [s for s in segment_predictions if s["prob_fake"] > 0.5]
        if fake_segments and overall_prob <= 0.85:
            clusters = []
            current_cluster = [fake_segments[0]]
            for s in fake_segments[1:]:
                last_s = current_cluster[-1]
                if s["start_time"] - last_s["start_time"] <= 0.6:
                    current_cluster.append(s)
                else:
                    clusters.append(current_cluster)
                    current_cluster = [s]
            clusters.append(current_cluster)
            biggest_cluster = max(clusters, key=len)
            start_time = biggest_cluster[0]["start_time"]
            end_time = biggest_cluster[-1]["end_time"]
            if len(biggest_cluster) >= 3:
                insights.append(f"Najwyższe stężenie cech deepfake występuje w fragmencie nagrania od {start_time:.1f}s do {end_time:.1f}s.")
            elif len(fake_segments) <= 3 and overall_prob < 0.5:
                peak = max(fake_segments, key=lambda x: x["prob_fake"])
                insights.append(f"Nagranie brzmi w większości naturalnie, jednak zidentyfikowano krótkie, podejrzane artefakty w okolicy {peak['start_time']:.1f}s - {peak['end_time']:.1f}s.")
        if not insights:
            insights.append("Analiza wykazuje niejednoznaczne cechy - zalecana dodatkowa weryfikacja.")
        return insights
    @INFERENCE_TIME.time()
    def analyze(self, file_path, progress_callback=None):
        path_16k = "/tmp/audio_16k.wav"
        path_22k = "/tmp/audio_22k.wav"
        self._extract_audio(file_path, 16000, path_16k)
        self._extract_audio(file_path, 22050, path_22k)
        wav_16k = self._load_wav(path_16k)
        wav_22k = self._load_wav(path_22k)
        duration_sec = len(wav_16k) / 16000.0
        chunk_len_16k = 16000
        step_16k = 8000
        chunk_len_22k = 22050
        step_22k = 11025
        num_chunks = math.floor((len(wav_16k) - chunk_len_16k) / step_16k) + 1
        if num_chunks < 1:
            num_chunks = 1
        # Pad so every window is full-length (the 16k/22k streams can round to a few samples
        # apart), so chunks can be stacked into batches without ragged shapes.
        need_16 = (num_chunks - 1) * step_16k + chunk_len_16k
        need_22 = (num_chunks - 1) * step_22k + chunk_len_22k
        if len(wav_16k) < need_16:
            wav_16k = torch.nn.functional.pad(wav_16k, (0, need_16 - len(wav_16k)))
        if len(wav_22k) < need_22:
            wav_22k = torch.nn.functional.pad(wav_22k, (0, need_22 - len(wav_22k)))
        # Pre-slice every chunk, then run both models in mini-batches: keeps the CPU cores busy
        # and cuts per-call overhead vs. one session.run() / forward per chunk.
        chunks_16 = np.stack([
            wav_16k[i * step_16k: i * step_16k + chunk_len_16k].numpy() for i in range(num_chunks)
        ])
        chunks_22 = torch.stack([
            wav_22k[i * step_22k: i * step_22k + chunk_len_22k] for i in range(num_chunks)
        ])
        probs = np.empty(num_chunks, dtype=np.float64)
        for b0 in range(0, num_chunks, _BATCH_SIZE):
            b1 = min(b0 + _BATCH_SIZE, num_chunks)
            w2v2_logits = self.w2v2_session.run(
                None, {"input_values": chunks_16[b0:b1]}
            )[0].reshape(b1 - b0, -1)[:, 0]
            prob_b = 1.0 / (1.0 + np.exp(-w2v2_logits))
            mel_spec = self.db_transform(self.mel_transform(chunks_22[b0:b1]))
            with torch.no_grad():
                mel_logits = self.mel_model(mel_spec.unsqueeze(1)).reshape(-1)
                prob_a = torch.sigmoid(mel_logits).numpy()
            probs[b0:b1] = 0.4 * prob_a + 0.6 * prob_b
            if progress_callback:
                progress_callback(int(b1 / num_chunks * 90))
        segment_predictions = [
            {"start_time": i * 0.5, "end_time": i * 0.5 + 1.0, "prob_fake": round(float(probs[i]), 4)}
            for i in range(num_chunks)
        ]
        worst_chunk_22k = chunks_22[int(np.argmax(probs))]
        gradcam_path = "/tmp/gradcam.png"
        if worst_chunk_22k is not None:
            self.generate_gradcam(worst_chunk_22k, gradcam_path)
        if progress_callback:
            progress_callback(100)
        if segment_predictions:
            overall_prob = sum(seg["prob_fake"] for seg in segment_predictions) / len(segment_predictions)
        else:
            overall_prob = 0.0
        insights = self._generate_insights(segment_predictions, overall_prob)
        return {
            "prob_fake": round(overall_prob, 4),
            "verdict": "FAKE" if overall_prob > 0.5 else "REAL",
            "confidence": round(abs(overall_prob - 0.5) * 2, 4),
            "model_version": "v1.1.0-ensemble",
            "local_gradcam_path": gradcam_path,
            "metadata": {
                "duration_seconds": round(duration_sec, 2),
                "segments_processed": num_chunks,
                "segment_predictions": segment_predictions,
                "insights": insights
            }
        }
