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
native — zero deps); detectors log structlog JSON. Alloy reads every container's stdout through
`/var/run/docker.sock` (read-only) and pushes to Loki.

- **Labels (low cardinality only):** `service` (compose service name), `level`.
- **Structured metadata (NOT labels):** `correlation_id`, `trace_id` — high cardinality would explode
  Loki streams. Query them with `{service="orchestrator"} | json | trace_id="..."`.
- The raw line always reaches Loki, so even if a JSON field path is off, `{service="..."} | json`
  parses it client-side. (See `infra/alloy/config.alloy` `#G6` — verify ECS field paths against
  `docker logs orchestrator` once the Java stack runs.)

## Metrics (D3)

Names and labels: [contracts/metrics.md](contracts/metrics.md). The business dashboard
(`infra/grafana/dashboards/deepfake-business.json`) plots throughput, cache hit ratio, in-flight
depth, analysis-duration p95 and HTTP p95.

Every Java service needs `micrometer-registry-prometheus` for `/actuator/prometheus` to exist (the
actuator starter ships only the disabled OTLP registry). Present in gateway/orchestrator/file-service;
eureka scrape is intentionally skipped (low value).

## Traces

`trace_id`/`span_id` reach the logs always (D2). With OTLP export on, spans ship to Tempo over
`tempo:4318`. Spans carry `analysis.id` / `analysis.type`. Grafana correlates both ways: a Loki
`trace_id` links to Tempo (derived field), and a Tempo trace links back to Loki (`tracesToLogsV2`).

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
