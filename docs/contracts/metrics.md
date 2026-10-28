# Metrics Contract

Custom business metrics (D3), exposed by the Orchestrator at `/actuator/prometheus` (permitAll —
internal scrape). Names below are the **Prometheus exposition** names (verified via `scrape()` in
`AnalysisMetricsTest`); in Java code they are dotted (Micrometer converts `.`→`_` and adds the
`_total` counter suffix without doubling). This file is the source for the provisioned business dashboard.

| Prometheus name              | Type    | Labels                                   | Meaning |
|------------------------------|---------|------------------------------------------|---------|
| `analyses_total`             | counter | `status` (completed/failed/cancelled), `type` (video/audio/full) | One per terminal transition. Funneled through `onTerminal`, so result, cancel, DLQ and stuck-recovery all count once. Recorded after commit, so a rolled-back transition counts nothing. |
| `analyses_deleted_total`     | counter | `status` (completed/failed/cancelled) | One per analysis the owner permanently deletes (`DELETE /api/analysis/{id}/record`), tagged by the terminal status it carried. Recorded after commit; a losing concurrent delete (`404`) does not count. Independent of `analyses_total` (a cumulative event counter, never decremented) — the live `GET /api/analysis/stats` aggregate is what drops the deleted row. |
| `analysis_duration_seconds`  | timer   | `type`                                   | End-to-end latency (created → completed). Recorded for `COMPLETED` only — duration is meaningless for an aborted analysis. Histogram buckets are enabled for p95 by type. |
| `cache_requests_total`       | counter | `result` (hit/miss)                      | `GET /api/analysis/{id}` cache lookups. Redis-down counts as `miss` (fail-open to DB), so a miss spike during a Redis outage is expected, not a bug. |
| `analyses_inflight`          | gauge   | —                                        | Committed active analyses (`PENDING`/`PROCESSING`) in PostgreSQL, using the same query as admission. Sampled each scrape; returns `0` if the database query fails. This is the brief's `queue_depth`. |
| `analyses_dlq_total`         | counter | —                                        | Analyses failed by the dead-letter consumer (D6). Counts only transitions that won the `failSourceIfUnaccepted` CAS, so it stays aligned with `analyses_total{status="failed"}` — a duplicate/late DLQ message for an already-terminal analysis does not count. |
| `analyses_stuck_recovered_total` | counter | —                                    | Analyses auto-failed by stuck-job recovery (D6). Same CAS-aligned semantics as `analyses_dlq_total`. |
| `amqp_listener_retries_total`| counter | —                                        | In-process redeliveries scheduled by the listener retry policy (D6), on the configured result listener factory. One message exhausting `max-retries: 3` (4 attempts) adds 3 (retries, not failed attempts). Lives in `ListenerRetryMetricsCustomizer`, not `AnalysisMetrics` — Boot 4.0 listener retry has no RetryListener hook, so the policy's BackOff is wrapped via `RabbitListenerRetrySettingsCustomizer`. |

Cardinality: labels are enum-valued only — never `analysis_id`/`user_id` (those go on **spans**, not
metric series). RabbitMQ queue depth (`rabbitmq_queue_messages`) comes from the RabbitMQ Prometheus
plugin, not from app code.

Every counter series above is registered at 0 at startup, one per label combination (terminal
statuses × types, terminal statuses, hit/miss), so a fresh stack shows 0 on the dashboard instead
of "No data". "No data" on a counter panel therefore means the target is not scraped. Percentiles
and the cache hit ratio have no value without observations; those panels say so (`noValue`).

## File Service (`/actuator/prometheus`)

| Prometheus name                 | Type    | Labels | Meaning |
|---------------------------------|---------|--------|---------|
| `files_purged_total`            | counter | —      | Soft-deleted files purged from S3 + DB by the cleanup sweep after the retention window (`storage.cleanup.retention-hours`). Counts only the replica that won the row hard-delete CAS, so D4 replication doesn't double-count. |
| `files_orphans_reclaimed_total` | counter | —      | Orphaned `deepfake-uploads` objects (no metadata row — the upload's S3 put succeeded but the row commit didn't) reclaimed once older than `storage.cleanup.orphan-min-age-hours`. |

## Gateway (`/actuator/prometheus`)

| Prometheus name | Type | Labels | Meaning |
|-----------------|------|--------|---------|
| `gateway_ratelimiter_degraded_total` | counter | `route` (`file-service`, `orchestrator`) | Requests forwarded without Redis rate limiting on error, timeout or `remaining=-1`, including the shared degraded bypass window. Uploads fail closed and do not increment this counter. |

Java name: `gateway.ratelimiter.degraded`. Route labels come from static gateway
configuration, never user IDs or URLs; every route behind the fail-open limiter is registered at 0
at startup (`DegradedRequestMeters`). The Business dashboard shows the request
rate by route. `GatewayRateLimitIntegrationTest` checks the actual Prometheus name.

## Detectors (`/metrics`)

Both FastAPI services expose the existing `prometheus_client` collectors. No metric
contains an analysis ID, user ID, file key or model path.

| Prometheus name | Type | Labels | Meaning |
|---|---|---|---|
| `inference_latency_seconds` | histogram | `source` (`video`) | Video runtime `process_task` wall time: download, inference and artifact upload; excludes pickup cancel and final result publication/ACK. Includes failed/interrupted attempts after processing starts. Uses the shared detector duration buckets below. |
| `detector_results_total` | counter | `source` (`video`), `outcome` (`fake`, `real`, `failed`, `cancelled`) | Video delivery outcomes. Completed and failed are recorded after result confirm and ACK; cancelled is recorded before ACK. Redelivered attempts can each count. |
| `video_stage_duration_seconds` | histogram | `stage` (`face_detection`, `backbone`, `temporal`, `gradcam`) | Time spent in a video pipeline stage, including a stage that raises. Uses the shared detector duration buckets below. |
| `video_model_load_duration_seconds` | gauge | — | Time of the last successful video model initialization. |
| `audio_inference_duration_seconds` | histogram | — | Audio `analyze` wall time, including extraction, preprocessing and Grad-CAM, but excluding S3 download/upload. Includes failed/interrupted calls. Uses the shared detector duration buckets below. |
| `audio_processed_total` | counter | `status` (`success`, `error`, `cancelled`) | Audio delivery outcomes. Success and error follow result confirm and ACK; cancelled precedes ACK. Redelivered attempts can each count. `NO_AUDIO_TRACK` is a detector error even when a FULL analysis later completes through video. |
| `audio_model_load_duration_seconds` | gauge | — | Time of the last successful audio model initialization. |

All three duration histograms use explicit finite buckets in seconds:
`0.1, 0.25, 0.5, 1, 2, 3, 4, 5, 8, 10, 15, 20, 30, 60, 120, 300, 600, 1200, 1800, 3600`.
At startup, a `DETECTOR_TASK_HARD_LIMIT_SECONDS` above 3600 appends that exact finite
bound, including fractional values. A lower configured limit keeps the base buckets.
The setting retains its existing positive finite validation and default of 3600 s;
these buckets do not change execution deadlines. Metric names and labels stay the same.

Every `detector_results_total` outcome and `audio_processed_total` status is exposed at 0 from
startup. Histogram exposition includes `_bucket{le}`, `_sum` and `_count`; all include an
infinite bucket. The detector dashboard row shows p95 separately for video and
audio, plus their existing outcome labels. These counters count delivery attempts,
not unique analyses: use `analyses_total` for committed analysis outcomes.
The two inference timers have different scopes, so their p95 values are not a
controlled comparison of pure model execution. The existing Business dashboard
queries aggregate by `le` and have no fixed latency ceiling, so they need no query
change; current alert rules do not reference these detector duration histograms.
Deploy the new bucket layout to all replicas together and allow the 5-minute rate
window to refresh before interpreting p95, since mixed layouts skew aggregation.
A p95 above the highest finite bucket saturates at that bucket; use the bucket distribution when investigating it.

## Percentile dashboards and alerts

`management.metrics.distribution.percentiles-histogram` enables
`analysis.duration` in the orchestrator and `http.server.requests` in gateway,
file-service and orchestrator. Prometheus exposes `analysis_duration_seconds_bucket`
and `http_server_requests_seconds_bucket`. Grafana uses
`histogram_quantile(0.95, sum by (le, type) (rate(...[5m])))` for analysis duration,
and `(le, job)` for HTTP, aggregating replicas before computing the percentile.
The finite histogram ranges extend to 2 hours for analysis duration (the existing
absolute recovery allowance) and 10 minutes for HTTP (the default SSE lifetime),
covering long analyses, uploads and streams instead of Micrometer's default 30 seconds.
These are histogram bounds, not execution deadlines; values beyond a finite range
can saturate p95 at its upper bound. Increase that bound if deploying longer requests.
HTTP panels include all observed routes per service. No observations means no p95
series; it does not mean zero latency. Histograms add bounded bucket series for
existing labels and do not introduce user/analysis labels.

The minimal rules in `infra/prometheus/alerts.yml` use existing metrics:
`AnalysisDeadLettered` and `AnalysisStuckRecovered` detect counter increases over
5 minutes; `AnalysisDeadLetterBacklog` detects a nonempty DLQ/quarantine for 2 minutes;
`ScrapeTargetDown` detects `up == 0` for 2 minutes, excluding the optional detector
jobs. `DetectorUnavailable` detects ready messages in `analysis.video` or
`analysis.audio` for 2 minutes when either no `up == 1` target exists in the corresponding
`video-detector` or `audio-detector` job (including absent DNS targets), or the queue's
`rabbitmq_queue_consumers{job="rabbitmq"}` count is zero despite healthy HTTP. Its labels
are `job`, `queue` and `severity`; ready work comes from the existing per-queue
`rabbitmq_queue_messages_ready{job="rabbitmq"}` metric. An empty queue, or a healthy
replica together with a consumer, resolves the alert. Absent consumer metrics preserve
the scrape-only check; missing queue metrics cannot establish pending work.
The broker already exports per-object metrics (`prometheus.return_per_object_metrics = true`).
Detector targets use DNS A discovery on port 8000 (refresh 30 s), with one IP-based
`instance` per replica; dashboards aggregate instances before p95/outcome display.
Metric names and application labels remain unchanged. Alert rules do not change
recovery thresholds or queue policies. See [observability](../observability.md#alerts)
for operation and validation.

## Tracing

OTLP trace export is gated by `OTEL_TRACING_EXPORT_ENABLED` (default `false`; explicitly enable it when using
the `monitoring` profile with Tempo). The tracer + Micrometer bridge are always on, so
`trace_id`/`span_id` reach the logs (D2). Spans carry `analysis.id` (create + result handling) and
`analysis.type` (create) so a trace is searchable by analysis in Tempo.

## Gateway scrape transport

Gateway metrics keep the `/actuator/prometheus` path on the dedicated management
port 9090 (`gateway:9090` inside Compose). Port 8080 rejects Actuator access, even
with an application JWT. Compose does not publish port 9090 on the host.
