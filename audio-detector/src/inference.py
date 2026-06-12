import os
import subprocess
import torch
import numpy as np
import soundfile as sf
import onnxruntime as ort
import matplotlib
matplotlib.use('Agg')
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
MEL_CKPT_PATH = os.path.join(PROJECT_ROOT, "training", "checkpoints", "mel_resnet", "last.ckpt")

MEL_EER_THRESHOLD = float(os.getenv("MEL_EER_THRESHOLD", "0.3049"))
W2V2_EER_THRESHOLD = float(os.getenv("W2V2_EER_THRESHOLD", "0.3000"))

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
        self.mel_module.eval()
        
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

    def generate_gradcam(self, audio_chunk_16k, out_png_path):
        self.mel_module.zero_grad()
        activations = []
        gradients = []

        def fwd_hook(module, input, output):
            activations.append(output)

        def bwd_hook(module, grad_in, grad_out):
            gradients.append(grad_out[0])

        target_layer = self.mel_module.model.resnet.layer4
        h1 = target_layer.register_forward_hook(fwd_hook)
        h2 = target_layer.register_full_backward_hook(bwd_hook)

        # audio_chunk_16k has shape (Time,). Make it (1, Time) and require grad.
        audio_in = audio_chunk_16k.unsqueeze(0).requires_grad_(True)
        logits = self.mel_module(audio_in)
        logits.backward()

        h1.remove()
        h2.remove()
        
        # Get mel spec for plotting
        mel_spec = self.mel_module.mel_transform(audio_chunk_16k.unsqueeze(0)).squeeze().detach()

        if not activations or not gradients:
            plt.imsave(out_png_path, mel_spec.numpy(), cmap='viridis')
            return out_png_path

        acts = activations[0].squeeze(0)
        grads = gradients[0].squeeze(0)

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
        plt.imshow(mel_spec.numpy(), aspect='auto', origin='lower', cmap='viridis')
        plt.imshow(cam, aspect='auto', origin='lower', cmap='jet', alpha=0.5)
        plt.axis('off')
        plt.tight_layout()
        plt.savefig(out_png_path, bbox_inches='tight', pad_inches=0)
        plt.close()

        return out_png_path

    def _generate_insights(self, segment_predictions, overall_prob, threshold):
        insights = []
        if not segment_predictions:
            return ["Brak danych do analizy odcinkowej."]

        if overall_prob > max(0.85, threshold + 0.35):
            insights.append("Całe nagranie wykazuje spójne, bardzo wysokie parametry syntezy AI - model wskazuje na mocną ingerencję lub całkowite wygenerowanie głosu.")
        elif overall_prob < min(0.15, threshold - 0.35):
            insights.append("Sygnał audio jest w pełni naturalny, nie wykryto żadnych śladów modulacji AI.")

        fake_segments = [s for s in segment_predictions if s["prob_fake"] > threshold]

        if fake_segments and overall_prob <= max(0.85, threshold + 0.35):
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
            elif len(fake_segments) <= 3 and overall_prob < threshold:
                peak = max(fake_segments, key=lambda x: x["prob_fake"])
                insights.append(f"Nagranie brzmi w większości naturalnie, jednak zidentyfikowano krótkie, podejrzane artefakty w okolicy {peak['start_time']:.1f}s - {peak['end_time']:.1f}s.")

        if not insights:
            insights.append("Analiza wykazuje niejednoznaczne cechy - zalecana dodatkowa weryfikacja.")

        return insights

    @INFERENCE_TIME.time()
    def analyze(self, file_path, mode="accurate", progress_callback=None):
        start_analysis = time.time()
        
        threshold = MEL_EER_THRESHOLD if mode == "fast" else W2V2_EER_THRESHOLD
        
        # Stage values are the contract set (amqp-messages.md): LOADING / PREPROCESSING /
        # INFERENCE / POSTPROCESSING — clients switch on them, no detector-specific names.
        if progress_callback:
            progress_callback(5, "PREPROCESSING")

        # Derive temp paths from file_path (carries the analysis id) — fixed /tmp names would
        # collide between concurrent tasks (prefetch > 1 or a second worker in one container).
        path_16k = f"{file_path}_16k.wav"
        self._extract_audio(file_path, 16000, path_16k)

        if progress_callback:
            progress_callback(10, "PREPROCESSING")

        wav_16k = self._load_wav(path_16k)
        if os.path.exists(path_16k):
            os.remove(path_16k)
        
        duration_sec = len(wav_16k) / 16000.0
        chunk_len_16k = 16000
        step_16k = 8000
        num_chunks = math.floor((len(wav_16k) - chunk_len_16k) / step_16k) + 1
        
        if num_chunks < 1:
            wav_16k = torch.nn.functional.pad(wav_16k, (0, chunk_len_16k - len(wav_16k)))
            num_chunks = 1
            
        segment_predictions = []
        highest_fake_prob = -1.0
        worst_chunk_16k = None
        
        for i in range(num_chunks):
            start_16k = i * step_16k
            end_16k = start_16k + chunk_len_16k
            chunk_16_tensor = wav_16k[start_16k:end_16k]
            
            # --- VAD (Voice Activity Detection) ---
            rms_energy = torch.sqrt(torch.mean(chunk_16_tensor ** 2))
            if rms_energy < 0.005:
                if progress_callback:
                    progress_callback(
                        15 + int((i / num_chunks) * 80),
                        "INFERENCE",
                        {"current_segment": i+1, "total_segments": num_chunks}
                    )
                continue
            # --------------------------------------
            
            if mode == "fast":
                with torch.no_grad():
                    mel_logits = self.mel_module(chunk_16_tensor.unsqueeze(0))
                    prob_a = torch.sigmoid(mel_logits).item()
                final_prob = float(prob_a)
            else:
                chunk_16_np = chunk_16_tensor.unsqueeze(0).numpy()
                w2v2_outs = self.w2v2_session.run(None, {"input_values": chunk_16_np})
                w2v2_logits = w2v2_outs[0][0][0]
                prob_b = 1.0 / (1.0 + np.exp(-w2v2_logits))
                final_prob = float(prob_b)
            start_time = i * 0.5
            end_time = start_time + 1.0
            
            segment_predictions.append({
                "start_time": start_time,
                "end_time": end_time,
                "prob_fake": round(final_prob, 4)
            })
            
            if final_prob > highest_fake_prob:
                highest_fake_prob = final_prob
                worst_chunk_16k = chunk_16_tensor
                
            if progress_callback:
                progress_callback(
                    15 + int((i / num_chunks) * 80),
                    "INFERENCE",
                    {"current_segment": i+1, "total_segments": num_chunks}
                )

        gradcam_path = f"{file_path}_gradcam.png"
        if worst_chunk_16k is not None:
            if progress_callback:
                progress_callback(95, "POSTPROCESSING")
            self.generate_gradcam(worst_chunk_16k, gradcam_path)

        if progress_callback:
            progress_callback(100, "POSTPROCESSING")
            
        if segment_predictions:
            raw_probs = [seg["prob_fake"] for seg in segment_predictions]
            
            # Wygładzanie (Moving Average z oknem 3-sekundowym)
            # Izolowane piki (np. trzask w mikrofonie, szum) znikną.
            # Aby uznać fragment za fake, anomalia musi trwać chociaż 2-3 sekundy ciągiem.
            smoothed_probs = []
            for i in range(len(raw_probs)):
                start_idx = max(0, i - 1)
                end_idx = min(len(raw_probs), i + 2)
                smoothed_probs.append(float(np.mean(raw_probs[start_idx:end_idx])))
                
            top_k = min(3, len(smoothed_probs))
            top_probs = sorted(smoothed_probs, reverse=True)[:top_k]
            overall_prob = float(np.mean(top_probs))
        else:
            overall_prob = threshold
            
        insights = self._generate_insights(segment_predictions, overall_prob, threshold)
        end_analysis = time.time()
        
        return {
            "prob_fake": round(overall_prob, 4),
            "verdict": "FAKE" if overall_prob > threshold else "REAL",
            "confidence": round(abs(overall_prob - threshold) / max(threshold, 1.0 - threshold), 4),
            "model_version": f"v1.2.0-{mode}",
            "local_gradcam_path": gradcam_path,
            "metadata": {
                "duration_seconds": round(duration_sec, 2),
                "analysis_time_seconds": round(end_analysis - start_analysis, 2),
                "segments_processed": num_chunks,
                "segment_predictions": segment_predictions,
                "insights": insights,
                "threshold_used": threshold,
                "mode_used": mode
            }
        }
