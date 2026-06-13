# AMQP Message Contracts

The broker (RabbitMQ) boots empty. Topology — exchanges, queues, bindings — is
declared by application code at startup (Spring AMQP `@Bean` in the Orchestrator,
`pika channel.queue_declare(...)` in detectors). This document is the single
source of truth: every publisher and consumer must declare with **exactly the
same arguments**, otherwise RabbitMQ will reject with `PRECONDITION_FAILED`.

## Topology

Exchange `analysis.exchange` — type `topic`, durable.
Exchange `analysis.dlx` — type `direct`, durable. Receives dead-lettered messages.

| Routing key         | Queue               | Publisher    | Consumer        |
|---------------------|---------------------|--------------|-----------------|
| `analysis.video`    | `analysis.video`    | Orchestrator | Video Detector  |
| `analysis.audio`    | `analysis.audio`    | Orchestrator | Audio Detector  |
| `analysis.progress` | `analysis.progress` | Detectors    | Orchestrator    |
| `analysis.results`  | `analysis.results`  | Detectors    | Orchestrator    |

Cancellation is deliberately **not** an AMQP event — see
[Cancellation](#cancellation-orchestrator--detector-via-redis) below. (The
`analysis.cancel` queue existed pre-V2 with no consumer; deployments that still
have it on the broker can delete it.)

Required arguments for `analysis.video` and `analysis.audio`:

- `durable: true`
- `x-dead-letter-exchange: analysis.dlx`
- `x-dead-letter-routing-key: analysis.<source>.dlq`

DLQ queues (`analysis.video.dlq`, `analysis.audio.dlq`) are bound to
`analysis.dlx` with a routing key equal to the queue name.

`analysis.progress` and `analysis.results` are `durable: true` with no further
arguments (no DLX).

`analysis.results.dlq` is bound to `analysis.dlx` with a routing key equal to the
queue name, but it is **not** reached by broker dead-lettering — it is fed by the
Orchestrator's `RepublishMessageRecoverer` after a result/progress listener exhausts
its retries. `analysis.results` therefore keeps **no** DLX arguments, so the detectors
that also declare it don't hit `PRECONDITION_FAILED`. Only the Orchestrator declares
and consumes `analysis.results.dlq`.

## Message formats

Convention: **snake_case** for AMQP payloads (Python-friendly). All fields
are required unless explicitly marked optional.

Beyond the JSON body, every service propagates the W3C `traceparent` header on
publish and consume: the Java services via Micrometer observation, the Python
detectors via a manual `TraceContextTextMapPropagator` extract/inject in
`consumer.py` (extract on the task message, inject on every progress/result), so
one Tempo trace spans HTTP → AMQP → detector → result. `correlation_id` lives in
the body and is the cross-language correlation id — detectors echo it on every
progress/result. A message without `traceparent` roots a new trace; processing
never depends on the header.

### Task — Orchestrator → Detector

Published to `analysis.video` or `analysis.audio`.

```json
{
  "analysis_id": "550e8400-e29b-41d4-a716-446655440000",
  "file_bucket": "deepfake-uploads",
  "file_key": "550e8400-e29b-41d4-a716-446655440000_test.mp4",
  "correlation_id": "corr-uuid",
  "timestamp": "2026-04-21T10:30:00Z",
  "mode": "accurate"
}
```

`mode` — **audio tasks only** (optional). Selects the audio model: `"fast"` =
lightweight spectrogram model, `"accurate"` = Wav2Vec2 waveform analysis (slower,
robust to recent generators, e.g. ElevenLabs). The Audio Detector falls back to
`"accurate"` when the field is missing; the Orchestrator nevertheless always sends
it explicitly (REST `mode` absent → `"accurate"`). Video tasks do not carry the
field.

`file_key` follows the convention from
[`object-storage.md`](./object-storage.md): full `{fileId}` UUID + `_` +
original filename. Never shorten the UUID — it's the collision-resistance
guarantee.

### Progress — Detector → Orchestrator

Published to `analysis.progress` while processing.

```json
{
  "analysis_id": "550e8400-e29b-41d4-a716-446655440000",
  "correlation_id": "corr-uuid",
  "source": "video",
  "progress": 50,
  "stage": "INFERENCE"
}
```

`stage` values: `LOADING`, `PREPROCESSING`, `INFERENCE`, `POSTPROCESSING`. This is a
closed set — clients switch on it, detectors must not invent their own names.

`details` (optional) — small detector-defined object with stage context, e.g. the audio
detector sends `{ "current_segment": 12, "total_segments": 40 }` during `INFERENCE`.
Consumers must tolerate its absence.

Detectors send a start-ping (`progress: 0`, `stage: LOADING`) as soon as they pick up
a task. The Orchestrator flips the analysis `PENDING` → `PROCESSING` on it and treats
every later progress as a heartbeat (bumps `updated_at`), so stuck-job recovery measures
silence since the last ping rather than age since start.

### Result — Detector → Orchestrator

Published to `analysis.results` on completion (success or failure).

Success:

```json
{
  "analysis_id": "550e8400-e29b-41d4-a716-446655440000",
  "correlation_id": "corr-uuid",
  "source": "video",
  "status": "COMPLETED",
  "result": {
    "prob_fake": 0.87,
    "verdict": "FAKE",
    "confidence": 0.74,
    "model_version": "dummy-v0.1",
    "gradcam_keys": ["550e8400-e29b-41d4-a716-446655440000/video/frame1.png"],
    "metadata": {}
  },
  "error": null
}
```

`gradcam_keys` — list of **bare object keys** in the `analysis-artifacts` bucket,
following the `{analysisId}/{source}/{name}.png` convention from
[`object-storage.md`](./object-storage.md). No URI scheme, no bucket prefix — the
Orchestrator persists the keys and serves the artifacts itself. Empty list when the
detector produced no visualizations. This is the only accepted field — URI-style
variants (`gradcam_url`, `gradcam_urls`) are ignored.

`metadata` — detector-defined free-form object (e.g. audio publishes
`segment_predictions`, `insights`, `duration_seconds`). The Orchestrator persists it
as-is, except `segment_predictions` is uniformly downsampled to ≤500 entries
(`segment_predictions_downsampled: true` is set when that happens).

Video publishes `duration_seconds`, `fps`, `frames_sampled`, `faces_detected` and
`frame_predictions` — a list of `{ "timestamp": 1.2, "attention": 0.18 }` entries with
the attention-pooling weights of the sampled frames (they sum to 1). **`attention` is
the frame's relative contribution to the clip-level verdict, not a per-frame
`prob_fake`** — the video model emits a single logit per clip, so clients must not
render it as a fake-probability timeline (audio's `segment_predictions` *are*
per-segment probabilities; these are not). On a REAL verdict the highest-attention
frames are the ones that most convinced the model of authenticity. Every `metadata`
field is source-specific and optional — consumers must not require a field published
by the other detector.

Known `error.code` values from the video detector: `VIDEO_DECODE_FAILED` (no decodable
frames), `NO_FACE_DETECTED` (face found in fewer than the minimum sampled frames),
`PROCESSING_ERROR` (generic fallback, shared with audio).

Failure: `status: "FAILED"`, `result: null`, `error: { "code": "...", "message": "..." }`.

### Cancellation — Orchestrator → Detector (via Redis)

Cancellation is cooperative and rides Redis, not AMQP. An AMQP cancel queue can't
work here: a shared queue round-robins each cancel to exactly one of N detector
consumers, and pika's `BlockingConnection` can't receive a broadcast while it is
busy processing — while the Redis flag also covers a task that is still waiting
in the queue, with no in-memory state to lose on restart.

Contract:

- On a committed cancel (`DELETE /api/analysis/{id}`) the Orchestrator sets the
  Redis key **`cancel:{analysis_id}`** = `"1"` with a 2 h TTL (after commit, so a
  rolled-back cancel can never abort a live analysis). One flag covers both
  sources of a FULL analysis.
- Detectors check the flag (`EXISTS`) at **task pickup** — cancelled-while-queued,
  the common case behind a long job with `prefetch=1` — and on **every progress
  tick**, so an in-flight inference aborts within one chunk/frame batch.
- On a hit the detector acks and drops the task, publishing **no** result and no
  further progress (the analysis is already terminal `CANCELLED` upstream; a
  `FAILED` result would be ignored anyway).
- Both sides fail open: Redis down ⇒ the flag is skipped / reads as absent, the
  detector simply finishes and the late result bounces off the Orchestrator's
  DB terminal-state guard — wasted compute, never a wrong state. The DB remains
  the correctness authority; the flag is purely a compute saver.

## Reliability (D6)

- **Retry:** the Orchestrator's `analysis.results` / `analysis.progress` listeners use
  property-based retry (4 attempts, exponential backoff 1s/4s/15s). Message-conversion
  errors are fatal — no retry, straight to the recoverer.
- **Dead-lettering:** detector task queues (`analysis.video` / `analysis.audio`) use broker
  DLX → `*.dlq`. Result/progress failures are instead republished by a
  `RepublishMessageRecoverer` to `analysis.results.dlq` (with `x-exception-*` headers), so
  `analysis.results` needs no DLX args (see Topology).
- **DLQ consumer:** one `@RabbitListener(ackMode="MANUAL")` drains `results/video/audio.dlq`
  → analysis `FAILED` + SSE notify + in-flight slot released. A missing/unparseable
  `analysis_id` is dropped (nack, no requeue); messages are never requeued onto a DLQ.
- **Manual ack:** literal `MANUAL` on the detectors and the DLQ consumer. The main
  result/progress listeners use `AUTO` = container ack **after** the `@Transactional`
  handler commits (not RabbitMQ auto-ack), which is what lets retry + recoverer compose.
- **Idempotency:** `dedup:{analysis_id}:{source}` in Redis, per-source (a FULL analysis
  yields two results under one id), set on commit so the key exists iff the result was
  persisted (fail-open). The DB terminal-state guard is the correctness authority.
- **Stuck-job recovery:** a scheduled scan fails analyses left `PENDING` / `PROCESSING`
  past a threshold (default 600s) → `FAILED` + slot released (gauge reconcile) + SSE.
- **Redis degradation:** every Redis touch fails open (cache → DB, gauge → admit, dedup →
  DB guard, progress snapshot → skipped). Redis is an accelerator, not the source of truth.
