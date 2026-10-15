# Live acceptance — media corpus and dependency faults (2026-10-15)

Run of [`tests/live/live_acceptance.py`](../../tests/live/README.md) against the full stack
(`core` + `auth` + `ml`, dev realm) at `main` after the cycle-2 fixes (#135–#202).

**Environment:** Windows 11, Docker Desktop 28.3 (WSL2, 8 GB / 12 vCPU for Docker), CPU-only
inference, one `file-service` replica, `BACKPRESSURE_MAX_INFLIGHT_PER_USER=100` and
`UPLOAD_RL_BURST=6000` (the corpus uploads from one service account). Input:
`load-tests/jmeter/sample-5mb.mp4` (55 s, 1280×720, speech), transformed with ffmpeg at runtime.

**Result:** 4/4 tests green; every fault landed while the analysis was still running.

## Upload validation

| Input | Result |
|---|---|
| video 4096×2160 | `422` `[MEDIA_RESOLUTION_TOO_HIGH] Rozdzielczość nagrania jest za wysoka (maks. 4K).` |
| MP3, 31 min | `422` `media duration exceeds 1800.0 seconds` |
| random bytes named `.mp4` | `422` `unsupported media type: application/octet-stream` |
| WebM (VP9) | `422` `unsupported media type: application/x-matroska` |

## Accepted media

| Input | Type | Outcome | Seconds |
|---|---|---|---|
| MP3 with a 3000×3000 cover (`attached_pic`) | AUDIO | COMPLETED, REAL | 28.6 |
| FLAC speech | AUDIO | COMPLETED, REAL | 24.5 |
| WAV uploaded as `.mp4` (MIME from magic bytes) | AUDIO | COMPLETED, REAL | 24.4 |
| AVI (MJPEG + PCM), 8 s | FULL | FAILED `NO_FACE_DETECTED` | 12.3 |
| portrait 1080×1920, 8 s | VIDEO | FAILED `NO_FACE_DETECTED` | 8.2 |
| video without audio, 8 s | FULL | FAILED `NO_FACE_DETECTED` | 4.2 |
| MP4 cut to a third of its bytes | FULL | FAILED `NO_FACE_DETECTED` | 8.2 |

The sample has no face, so every video path ends with the domain `NO_FACE_DETECTED`; none ended
with `ANALYSIS_TIMEOUT`, `DEAD_LETTERED` or `PROCESSING_ERROR`. The truncated MP4 passed upload
validation and reached the same domain outcome as the intact file.

## Faults during an analysis

Baselines without faults: AUDIO, 220 s of speech → COMPLETED/REAL in 75.5 s; VIDEO, 220 s without
a face → FAILED `NO_FACE_DETECTED` in 10.6 s. Each fault used the same input and was injected
5 s after the analysis entered `PROCESSING` (SeaweedFS: right after creation, during the input
download; video-detector: as soon as processing started).

| Fault | Input | Outcome | Seconds after fault |
|---|---|---|---|
| `restart postgres` | AUDIO | COMPLETED, REAL (= baseline) | 63.0 |
| `restart rabbitmq` | AUDIO | COMPLETED, REAL (= baseline) | 79.3 |
| `restart redis` | AUDIO | COMPLETED, REAL (= baseline) | 63.1 |
| `restart seaweedfs` | AUDIO | COMPLETED, REAL (= baseline) | 107.6 |
| `restart -t 0 orchestrator` (crash) | AUDIO | COMPLETED, REAL (= baseline) | 51.0 |
| `restart -t 0 audio-detector` (crash) | AUDIO | COMPLETED, REAL (= baseline) | 87.6 |
| `restart -t 0 video-detector` (crash) | VIDEO | FAILED `NO_FACE_DETECTED` (= baseline) | 18.3 |

`analysis.{video,audio,results}.dlq` and `analysis.backend.quarantine` did not grow during the run.

## Findings

- **Fixed (#202):** the first uploads after a gateway start returned `503` for about 2 s. The cold
  limiter script call exceeded the 250 ms Redis command timeout and opened the shared fail-closed
  window. It was found by the Playwright E2E run, which is now in CI (#203).
- **Fixed (#137, #179):** before this run, CI showed that a PostgreSQL shutdown could send valid
  results to `analysis.results.dlq`. The `restart postgres` row above confirms the fix end to end.
- File-service `422` messages for duration and format are technical English text; the frontend
  maps `422` to Polish messages by content (#142), so users do not see them.
- A video that reaches a verdict is covered by the face run below; the footage stays outside
  the repository. Repeat it locally with `E2E_VERDICT_FIXTURE` (Playwright) or `LIVE_SAMPLE`.

## Video with a real face (2026-10-15)

Input: the White House weekly address of 2017-01-07 (public domain, Wikimedia Commons), one
speaker facing the camera. It was transcoded locally to MP4 (H.264 720p, AAC) and cut to
30, 60 and 180 s. Stack: main at c650a59, `core` + `auth` + `ml`, one replica of each service,
Docker Desktop with 14 GB, Defender exclusion as in the [D4 report](d4-replication.md). Detector
times come from the detectors' own histograms (`inference_latency_seconds`,
`video_stage_duration_seconds`, `audio_inference_duration_seconds`). One warm-up run (video
24.4 s, of which 19.7 s was the first face detection) is excluded.

| Clip | Wall time (s) | Outcome | `videoProb` | `audioProb` | Video (s) | of which face detection (s) | Audio (s) |
|---|---|---|---|---|---|---|---|
| 30 s | 16.5 | COMPLETED, REAL | 0.041 | 0.026 | 7.9 | 1.7 | 12.6 |
| 30 s | 16.4 | COMPLETED, REAL | 0.041 | 0.026 | 5.7 | 2.0 | 12.4 |
| 30 s | 20.6 | COMPLETED, REAL | 0.041 | 0.026 | 10.3 | 4.2 | 16.4 |
| 60 s | 28.7 | COMPLETED, REAL | 0.044 | 0.014 | 10.7 | 4.3 | 24.8 |
| 180 s | 65.5 | COMPLETED, REAL | 0.042 | 0.006 | 10.8 | 4.7 | 57.2 |

Six FULL analyses of the 30 s clip created at once (`tests/live/detector_scaling.py`) all ended
COMPLETED/REAL after 101.9 s (212 per hour). That is no faster than six in a row: each detector
takes one task at a time.

- Every clip of the real speaker got the correct verdict. Repeated runs of the same clip gave
  identical probabilities.
- Video time stays at about 6–11 s regardless of length, because the detector samples a fixed
  number of frames. The AGENTS.md goal for a 30 s video is p95 < 8 s; two of three runs met it.
- Audio grows linearly at about 0.32× real time (30 s in about 12.5 s). The goal for 30 s of
  audio is < 3 s, so audio is not met and sets the wall time of a FULL analysis.
