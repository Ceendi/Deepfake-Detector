import json
import math
import os
import subprocess

import matplotlib
import numpy as np
import onnxruntime as ort
import soundfile as sf
import torch
from detector_runtime import MediaTooLongError

matplotlib.use('Agg')
import sys
import time

import matplotlib.pyplot as plt
from prometheus_client import Gauge, Histogram

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
sys.path.append(PROJECT_ROOT)

from training.train_mel import MelCNNLightningModule

from .score_contract import build_audio_result

W2V2_ONNX_PATH = os.path.join(PROJECT_ROOT, "training", "checkpoints", "w2v2", "w2v2.onnx")
MEL_CKPT_PATH = os.path.join(
    PROJECT_ROOT, "training", "checkpoints", "mel_resnet", "mel_model-epoch=49-val_eer=0.0816.ckpt"
)

MEL_EER_THRESHOLD = float(os.getenv("MEL_EER_THRESHOLD", "0.3049"))
W2V2_EER_THRESHOLD = float(os.getenv("W2V2_EER_THRESHOLD", "0.3000"))
MEDIA_MAX_DURATION_SECONDS = float(os.getenv("MEDIA_MAX_DURATION_SECONDS", "1800"))
if not math.isfinite(MEDIA_MAX_DURATION_SECONDS) or MEDIA_MAX_DURATION_SECONDS <= 0:
    raise ValueError("Media duration limit must be finite and positive")


class NoAudioTrackError(RuntimeError):
    code = "NO_AUDIO_TRACK"

    def __init__(self):
        super().__init__("Materiał nie zawiera ścieżki audio.")


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
        # Only a successful stream probe can distinguish absent audio from corrupt media.
        try:
            probe = subprocess.run([
                "ffprobe", "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=index", "-of", "json", input_path,
            ], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False, timeout=10)
            streams = json.loads(probe.stdout)["streams"] if probe.returncode == 0 else None
            if not isinstance(streams, list):
                raise TypeError("Invalid stream probe")
        except (OSError, subprocess.TimeoutExpired, ValueError, TypeError, KeyError):
            raise RuntimeError("Nie udało się sprawdzić ścieżki audio w materiale.") from None
        if not streams:
            raise NoAudioTrackError()
        try:
            proc = subprocess.run([
                "ffmpeg", "-y", "-i", input_path,
                "-vn", "-t", str(MEDIA_MAX_DURATION_SECONDS + 1 / sr),
                "-ar", str(sr), "-ac", "1", out_path
            ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        except OSError:
            raise RuntimeError("Nie udało się wyodrębnić ścieżki audio z materiału.") from None
        if proc.returncode != 0:
            raise RuntimeError("Nie udało się wyodrębnić ścieżki audio z materiału.")

    def _check_duration(self, path):
        try:
            proc = subprocess.run([
                "ffprobe", "-v", "quiet", "-show_entries", "format=duration",
                "-of", "json", path,
            ], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False, timeout=10)
        except subprocess.TimeoutExpired:
            raise RuntimeError("Media duration probe timed out.") from None
        if proc.returncode != 0:
            return
        try:
            duration = float(json.loads(proc.stdout)["format"]["duration"])
        except (ValueError, TypeError, KeyError):
            return
        if math.isfinite(duration) and duration > MEDIA_MAX_DURATION_SECONDS:
            raise MediaTooLongError()

    def _load_wav(self, path):
        # torchaudio 2.11 routes load() through torchcodec (not bundled); soundfile is already a
        # dependency and backend-independent. ffmpeg wrote mono wavs, so take channel 0.
        # Inspect the header before allocating samples; bound unknown/misreported input too.
        with sf.SoundFile(path) as audio:
            max_frames = math.floor(MEDIA_MAX_DURATION_SECONDS * audio.samplerate)
            if audio.frames > max_frames:
                raise MediaTooLongError()
            data = audio.read(frames=max_frames + 1, dtype="float32", always_2d=True)
            if len(data) > max_frames:
                raise MediaTooLongError()
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
        self._check_duration(file_path)
        try:
            self._extract_audio(file_path, 16000, path_16k)
            if progress_callback:
                progress_callback(10, "PREPROCESSING")
            wav_16k = self._load_wav(path_16k)
        finally:
            if os.path.exists(path_16k):
                os.remove(path_16k)
        
        num_samples = len(wav_16k)
        duration_sec = num_samples / 16000.0
        chunk_len_16k = 16000
        step_16k = 8000
        last_start = max(0, num_samples - chunk_len_16k)
        chunk_starts = list(range(0, last_start + 1, step_16k))
        # Retain regular windows and cover any remaining tail with one end-aligned window.
        if chunk_starts[-1] != last_start:
            chunk_starts.append(last_start)
        num_chunks = len(chunk_starts)

        if num_samples < chunk_len_16k:
            wav_16k = torch.nn.functional.pad(wav_16k, (0, chunk_len_16k - num_samples))
            
        segment_predictions = []
        highest_fake_prob = -1.0
        worst_chunk_16k = None
        
        for i, start_16k in enumerate(chunk_starts):
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
            start_time = start_16k / 16000.0
            end_time = min(end_16k, num_samples) / 16000.0
            
            segment_predictions.append({
                "start_time": start_time,
                "end_time": end_time,
                "raw_prob_fake": final_prob
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

        # Reject absent model evidence before emitting completion progress or artifacts.
        result = build_audio_result(segment_predictions, threshold, mode)
        gradcam_path = f"{file_path}_gradcam.png"
        if worst_chunk_16k is not None:
            if progress_callback:
                progress_callback(95, "POSTPROCESSING")
            self.generate_gradcam(worst_chunk_16k, gradcam_path)

        if progress_callback:
            progress_callback(100, "POSTPROCESSING")
            
        result["local_gradcam_path"] = gradcam_path
        result["metadata"].update({
            "duration_seconds": duration_sec,
            "analysis_time_seconds": round(time.time() - start_analysis, 2),
            "segments_processed": num_chunks,
        })
        return result
