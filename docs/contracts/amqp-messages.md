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

## Message formats

Convention: **snake_case** for AMQP payloads (Python-friendly). All fields
are required unless explicitly marked optional.

### Task — Orchestrator → Detector

Published to `analysis.video` or `analysis.audio`.

```json
{
  "analysis_id": "550e8400-e29b-41d4-a716-446655440000",
  "file_bucket": "deepfake-uploads",
  "file_key": "550e8400-e29b-41d4-a716-446655440000_test.mp4",
  "correlation_id": "corr-uuid",
  "timestamp": "2026-04-21T10:30:00Z"
}
```

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

`stage` values: `LOADING`, `PREPROCESSING`, `INFERENCE`, `POSTPROCESSING`.

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
    "gradcam_urls": [],
    "metadata": {}
  },
  "error": null
}
```

Failure: `status: "FAILED"`, `result: null`, `error: { "code": "...", "message": "..." }`.
