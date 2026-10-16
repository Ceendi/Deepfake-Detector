# Observability

The `monitoring` compose profile is a self-contained stack — no host plugins, runs on a clean
machine. It realises **D2** (centralised logs + correlation) and **D3** (metrics + dashboards), plus
distributed tracing.

```
docker compose --profile core --profile monitoring up         # + --profile auth --profile ml for full e2e
```

To see **traces** in Grafana, also set `OTEL_TRACING_EXPORT_ENABLED=true` (in `.env` or inline) — it
is gated off by default so core-only dev doesn't push to a non-existent collector.

## Components

| Service    | Port (127.0.0.1) | Role |
|------------|------------------|------|
| Grafana    | 3000             | UI (admin / `GF_SECURITY_ADMIN_PASSWORD`). Datasources + dashboards provisioned. |
| Prometheus | 9090             | Scrapes `/actuator/prometheus` (Java) + `/metrics` (detectors). Retention 15d. |
| Loki       | 3100             | Log store (tsdb, schema v13). Retention 7d (compactor). |
| Tempo      | 3200             | Trace store; OTLP receivers on 4317/4318 (internal). Retention 72h. |
| Alloy      | 12345            | Tails container stdout via the Docker socket (RO) → Loki. River config. |

## Logs (D2)

Java services log **ECS JSON** in-container (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, Spring Boot 4
native — zero deps); detectors log structlog JSON. Alloy reads this Compose project's container stdout through
`/var/run/docker.sock` (read-only) and pushes to Loki.

- **Labels (low cardinality only):** `service` (compose service name), `level`.
- **Structured metadata (NOT labels):** `correlation_id`, `trace_id` — high cardinality would explode
  Loki streams. Query them with `{service="orchestrator"} | json | trace_id="..."`.
- Alloy reads both the detector field names and the Java ECS ones (`correlationId`, `traceId`).
  The raw line always reaches Loki, so `{service="..."} | json` still works if a field is missing.

## Metrics (D3)

Names and labels: [contracts/metrics.md](contracts/metrics.md). The business dashboard
(`infra/grafana/dashboards/deepfake-business.json`) plots throughput, cache hit ratio, in-flight
depth, analysis-duration p95 by type, HTTP p95 by service, detector inference p95
and delivery outcomes. Histograms aggregate replicas before percentile calculation;
see the contract for timer scopes and labels.

Every Java service needs `micrometer-registry-prometheus` for `/actuator/prometheus` to exist (the
actuator starter ships only the disabled OTLP registry). Present in gateway/orchestrator/file-service;
eureka scrape is intentionally skipped (low value).

File-service uses DNS discovery (`type: A`, port 8081), refreshed every 30 s. Each address
returned for the Compose service becomes a separate target and `instance`, so all replicas are
scraped and scaling/recreation updates the target list. The `job="file-service"` label and
`/actuator/prometheus` path stay the same. `python3 infra/tests/test_core_reliability.py` verifies
healthy scrapes of the real replicas during a 2 → 1 → 2 scale cycle.

Video and audio detectors also use DNS discovery (`type: A`, port 8000, refresh 30 s),
keeping their `job` labels and `/metrics` path. Each replica has a separate IP-based
`instance`. The detector p95 panels sum histogram buckets across instances before
computing the percentile; outcome panels sum rates by outcome/status. Their legends
therefore stay stable when replicas change. `python3 infra/tests/test_monitoring_configuration.py`
checks both detector jobs during a 2 → 1 → 2 cycle using lightweight HTTP metrics
fixtures, without ML models or worker consumers.

Gateway's container healthcheck uses `http://localhost:9090/actuator/health/liveness`,
with Actuator probes enabled. Redis outages leave the live gateway container healthy;
the full `/actuator/health` remains available on the internal management port and
reports dependency failures for diagnostics. Application port 8080 still rejects
Actuator requests. Uploads retain their existing fail-closed 503 behavior and other
routes retain fail-open rate limiting. The core reliability test stops Redis and
checks diagnostic health, liveness and the automatic container probe before restoring it.

## Alerts

Prometheus loads `infra/prometheus/alerts.yml` from a read-only Compose mount.
The rules detect DLQ failures, stuck recovery, persistent DLQ/quarantine backlog
and unavailable scrape targets. Inspect pending/firing rules at
`http://localhost:9090/alerts`; no Alertmanager or outbound notification channel
is configured. `ScrapeTargetDown` excludes `video-detector` and `audio-detector`,
so disabling the optional `ml` profile with empty task queues does not trigger it.
`DetectorUnavailable` fires after 2 minutes only when `analysis.video` or
`analysis.audio` has ready messages and either no healthy scrape target exists for the
corresponding detector job or `rabbitmq_queue_consumers` reports zero consumers for that queue.
HTTP `/metrics` can remain healthy during an AMQP reconnect loop (bad credentials or
`PRECONDITION_FAILED`). It derives the job from the queue name and matches replicas by `job`;
an audio consumer cannot hide a video outage. One healthy replica suppresses scrape failure
only for its own job; a measured zero consumer count still fires. It clears when work drains
or both HTTP availability and consumer presence recover. If the consumer metric is absent,
only the existing scrape check applies; absence is not interpreted as zero.
Consumer presence does not prove worker progress; stuck recovery remains independent.
If RabbitMQ queue metrics are unavailable, the detector alert cannot infer pending
work; the broker's own `ScrapeTargetDown` reports that scrape failure instead.

DLQ/quarantine backlog calls for inspection of the original message and service
logs. Stuck recovery means the existing recovery deadlines failed an analysis;
check detector health, dispatch and per-source progress. `up == 0` indicates a
scrape failure, not necessarily a stopped process. Counter event alerts clear
when the event leaves the 5-minute window; persistent-state alerts clear on the
next healthy evaluation. Recreate Prometheus after changing rules or scrape config.

`python3 infra/tests/test_observability_configuration.py` validates Compose
operator overrides and dashboard queries, then uses the Compose Prometheus image's
`promtool check config` and `promtool test rules`. Fixtures prove alert activation,
the 2-minute hold, resolution, counter resets, short-outage suppression, missing
detector DNS targets, empty queues, partial replica outages and healthy HTTP without consumers.

## Traces

`trace_id`/`span_id` reach the logs always (D2). With OTLP export on, spans ship to Tempo over
`tempo:4318`. Spans carry `analysis.id` / `analysis.type`. Grafana correlates both ways: a Loki
`trace_id` links to Tempo (derived field), and a Tempo trace links back to Loki (`tracesToLogsV2`).

Tempo 3 runs monolithic with local storage (`infra/tempo/tempo.yaml`, no Kafka): the live-store
WAL is under `/var/tempo/live-store/traces`, completed blocks under `/var/tempo/blocks`, both in the
`tempo-data` volume. Tempo 3 rejects the Tempo 2 `ingester`/`compactor` sections; retention is
`overrides.defaults.compaction.block_retention`. `python3 infra/tests/test_tempo.py` checks
ingestion, retention settings and trace lookup after restart in a disposable project.

## Decisions

- **Native ECS logging**, not `logstash-logback-encoder` (Spring Boot 4 has it built in).
- **Grafana Alloy**, not the Loki Docker driver (host plugin, not clean-machine friendly) or Promtail
  (EOL 2026-03).
- **Dev trade-offs:** prometheus/loki/tempo/alloy run rootful with named volumes; Alloy mounts the
  Docker socket RO. Loki/Tempo/Alloy have no compose healthcheck (distroless images — no shell/wget),
  so dependents wait on `service_started`. App/DB containers stay hardened.

## Local verification

1. `docker compose --profile monitoring up -d` → all 5 up (prometheus/grafana healthy; loki/tempo/alloy
   have no healthcheck by design).
2. Grafana → datasources Prometheus/Loki/Tempo provisioned; the business dashboard loads.
3. `{service=~".+"}` in Loki returns container logs (Alloy pipeline works).
4. With `core` up: Prometheus targets for orchestrator/gateway/file-service go `up`; the dashboard
   shows real `analyses_total` / cache ratio / duration after an analysis.
5. With OTLP export on: a request produces a Tempo trace; click `trace_id` in Loki → Tempo and back.
