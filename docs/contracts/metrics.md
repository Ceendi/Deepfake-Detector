# Metrics Contract

Custom business metrics (D3), exposed by the Orchestrator at `/actuator/prometheus` (permitAll —
internal scrape). Names below are the **Prometheus exposition** names (verified via `scrape()` in
`AnalysisMetricsTest`); in Java code they are dotted (Micrometer converts `.`→`_` and adds the
`_total` counter suffix without doubling). This file is the single source for the PR4 dashboards.

| Prometheus name              | Type    | Labels                                   | Meaning |
|------------------------------|---------|------------------------------------------|---------|
| `analyses_total`             | counter | `status` (completed/failed/cancelled), `type` (video/audio/full) | One per terminal transition. Funneled through `onTerminal`, so result, cancel, DLQ and stuck-recovery all count once. |
| `analysis_duration_seconds`  | timer   | `type`                                   | End-to-end latency (created → completed). Recorded for `COMPLETED` only — duration is meaningless for an aborted analysis. |
| `cache_requests_total`       | counter | `result` (hit/miss)                      | `GET /api/analysis/{id}` cache lookups. Redis-down counts as `miss` (fail-open to DB), so a miss spike during a Redis outage is expected, not a bug. |
| `analyses_inflight`          | gauge   | —                                        | In-flight analyses (the backpressure counter `analyses:inflight` in Redis). Sampled each scrape, fail-open to `0` if Redis is down. This is the brief's `queue_depth`. |

Cardinality: labels are enum-valued only — never `analysis_id`/`user_id` (those go on **spans**, not
metric series). RabbitMQ queue depth (`rabbitmq_queue_messages`) comes from the RabbitMQ Prometheus
plugin in PR4, not from app code.

## Tracing

OTLP trace export is gated by `OTEL_TRACING_EXPORT_ENABLED` (default `false`; PR4 flips it on under
the `monitoring` profile once Tempo exists). The tracer + Micrometer bridge are always on, so
`trace_id`/`span_id` reach the logs (D2). Spans carry `analysis.id` (create + result handling) and
`analysis.type` (create) so a trace is searchable by analysis in Tempo.

> **Cross-service heads-up (PR4):** gateway, file-service and eureka also need
> `micrometer-registry-prometheus` on the classpath for their `/actuator/prometheus` to exist before
> Prometheus can scrape them.
