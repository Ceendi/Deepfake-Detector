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
| `analysis.cancel`   | `analysis.cancel`   | Orchestrator | Detectors (V2)  |

Required arguments for `analysis.video` and `analysis.audio`:

- `durable: true`
- `x-dead-letter-exchange: analysis.dlx`
- `x-dead-letter-routing-key: analysis.<source>.dlq`

DLQ queues (`analysis.video.dlq`, `analysis.audio.dlq`) are bound to
`analysis.dlx` with a routing key equal to the queue name.

`analysis.progress`, `analysis.results`, and `analysis.cancel` are `durable: true`
with no further arguments (no DLX).

`analysis.results.dlq` is bound to `analysis.dlx` with a routing key equal to the
queue name, but it is **not** reached by broker dead-lettering — it is fed by the
Orchestrator's `RepublishMessageRecoverer` after a result/progress listener exhausts
its retries. `analysis.results` therefore keeps **no** DLX arguments, so the detectors
that also declare it don't hit `PRECONDITION_FAILED`. Only the Orchestrator declares
and consumes `analysis.results.dlq`.

## Message formats

Convention: **snake_case** for AMQP payloads (Python-friendly). All fields
are required unless explicitly marked optional.

Beyond the JSON body, the Java services propagate the W3C `traceparent` header on
publish and consume (Micrometer observation → distributed tracing). `correlation_id`
lives in the body and is the cross-language correlation id — detectors echo it on
every progress/result. Python detectors do not yet propagate `traceparent` (planned:
`opentelemetry-instrumentation-pika`).

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

Failure: `status: "FAILED"`, `result: null`, `error: { "code": "...", "message": "..." }`.

### Cancel — Orchestrator → Detector

Published to `analysis.cancel` when a user cancels an analysis (`DELETE
/api/analysis/{id}`). The queue is declared so the event isn't dropped by the topic
exchange, but has no consumer yet — detectors consume it in V2 to stop in-progress
work early.

```json
{
  "analysis_id": "550e8400-e29b-41d4-a716-446655440000",
  "correlation_id": "corr-uuid"
}
```

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
