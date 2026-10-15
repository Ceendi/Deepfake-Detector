# Detector scaling — audio analyses with one vs two workers (2026-10-15)

**Question:** do more `audio-detector` containers drain the `analysis.audio` queue faster?

## Setup

- **Stack:** `core` + `auth` + `ml` at main after #209, one replica of every other service.
  Docker Desktop (WSL2, 14 GB, 12 vCPU, Defender exclusion from the
  [D4 report](d4-replication.md)) on an AMD Ryzen 5 5600 (6 cores / 12 threads).
- **Workload:** 12 uploads of the same 55 s speech FLAC (mono 16 kHz, from
  `load-tests/jmeter/sample-5mb.mp4`). All 12 `AUDIO` analyses are created at once after the
  uploads, so the timer covers only queueing and detector work. Every run is preceded by a
  2-analysis warm-up after the detectors start.
- **Metric:** makespan, from the first create to the last terminal status, and analyses per
  hour. All 96 measured analyses ended `COMPLETED`/`REAL`.
- **Variable:** detector count (`--scale audio-detector=N`) and `AUDIO_ONNX_INTRA_THREADS`.
  This sets ONNX Runtime intra-op threads and torch threads. Before this change the audio
  detector had no thread setting.

## Results

| Run | Detectors | Threads each | Makespan (s) | Analyses/h | Avg CPU per detector |
|---|---|---|---|---|---|
| A1 | 1 | library default | 223 | 194 | 993 % |
| A2 | 2 | library default | 369 | 117 | 530 % |
| A3 | 2, pinned to CPUs 0–5 / 6–11 | library default | 466 | 93 | 520 % |
| M1 | 1 | library default | 190 | 227 | 949 % |
| M5 | 1 | 4 | 204 | 211 | — |
| M2 | 1 | 6 | 181 | 239 | 498 % |
| **M3** | **2** | **6** | **146** | **297** | **445 %** |
| M4 | 2 | 3 | 167 | 259 | 242 % |

A1–A3 ran on main before this change. M1–M5 ran with it, where the library default is
`AUDIO_ONNX_INTRA_THREADS=0`.

## Interpretation

- **Unbounded pools oversubscribe the host.** By default ONNX Runtime and torch size their
  thread pools to the whole host, and one detector already keeps about 10 of 12 hardware threads
  busy. A second such detector made the queue drain 40 % slower (A2). Pinning each one to half of
  the CPUs did not help (A3), because the pools stayed oversized for their share.
- **Six threads per detector fix it.** One detector with 6 threads is as fast as with the
  default (M2 vs M1) and uses half the CPU. Two detectors with 6 threads each drain the queue
  31 % faster than one default detector (M3 vs M1). Three threads each is too few (M4).
- **The gain is sublinear on one host.** Two workers share memory bandwidth and the L3 cache.
  Separate machines do not, so on AWS one detector per instance with threads equal to its vCPUs
  should scale closer to linearly. That is an expectation, not a measurement.
- **Latency of a single analysis does not improve.** One 55 s file takes about 18–21 s in
  every variant. More workers only shorten the wait in the queue. That is about 10 s per 30 s of
  audio, against the AGENTS.md goal of < 3 s; the model, not the scaling, sets that.

## Configuration

`AUDIO_ONNX_INTRA_THREADS` defaults to 6 in `docker-compose.yml`, the fastest single-detector
value here. `VIDEO_ONNX_INTRA_THREADS` keeps the existing default of 4 and is now configurable
too. Set both to at most the CPUs a detector container really gets. On a shared host, divide
the cores between detector containers. In a container that only sees a CPU quota, such as an
ECS task, the libraries may still count every host core.

The global `BACKPRESSURE_MAX_INFLIGHT` (default 20) also caps how many analyses can be active,
so raise it together with the detector count.

## Threats to validity

- One host, one input file and one run per variant, so differences under about 10 % are noise
  (compare M1 and A1, the same configuration on two stack starts).
- Speech only. The video detector was not measured here, because the sample has no face and video
  stops early with `NO_FACE_DETECTED`.

## Reproduce

With the stack from [`tests/live/README.md`](../../tests/live/README.md) and a speech file:

```bash
AUDIO_ONNX_INTRA_THREADS=6 docker compose -p dfd-live --env-file live.env \
  --profile core --profile auth --profile ml up -d --wait --force-recreate --scale audio-detector=2 audio-detector
python tests/live/detector_scaling.py AUDIO speech.flac 2 warmup
python tests/live/detector_scaling.py AUDIO speech.flac 12 two-detectors results.jsonl
```
