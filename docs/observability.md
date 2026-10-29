# Observability

The `monitoring` Compose profile is a self-contained stack (no host plugins). It covers **D2**
(centralised logs with correlation), **D3** (metrics and dashboards) and distributed tracing.
Start it by adding `monitoring` to `COMPOSE_PROFILES` in `.env`, or explicitly:

```bash
docker compose --profile core --profile monitoring up -d    # add auth and ml for the whole app
```

Traces are exported only with `OTEL_TRACING_EXPORT_ENABLED=true` in `.env` (off by default, so a
stack without Tempo does not push to a missing collector). Containers read it at start: recreate
them with `--force-recreate` after changing it.

## Components

| Service    | Port (127.0.0.1) | Role |
|------------|------------------|------|
| Grafana    | 3000             | UI (admin / `GF_SECURITY_ADMIN_PASSWORD`); datasources and dashboards provisioned |
| Prometheus | 9090             | Scrapes `/actuator/prometheus` (Java) and `/metrics` (detectors); retention 15 d |
| Loki       | 3100             | Log store; retention 7 d |
| Tempo      | 3200             | Trace store, OTLP on 4317/4318 (internal); retention 72 h |
| Alloy      | 12345            | Tails this Compose project's container stdout through the Docker socket (full API access, dev-only) into Loki |

## Logs (D2)

Java services log ECS JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, built into Spring Boot 4);
detectors log structlog JSON. Alloy reads both field styles (`correlation_id`/`trace_id` and
`correlationId`/`traceId`).

- **Labels** (low cardinality only): `service` (Compose service name), `level`.
- **Structured metadata**, not labels: `correlation_id`, `trace_id`. Query them with
  `{service="orchestrator"} | json | trace_id="..."`. The raw line always reaches Loki.

## Metrics (D3)

Names, labels and timer scopes: [contracts/metrics.md](contracts/metrics.md). The business dashboard
(`infra/grafana/dashboards/deepfake-business.json`) shows throughput, cache hit ratio, in-flight
analyses, analysis duration p95 by type, HTTP p95 by service, detector inference p95 and delivery
outcomes. Percentiles are computed after summing histogram buckets across replicas, so panels stay
stable when replicas change.

Gateway, orchestrator and file-service expose `/actuator/prometheus` (`micrometer-registry-prometheus`);
Eureka is not scraped. File-service and both detectors are discovered through DNS (`type: A`,
refresh 30 s), so every replica is a separate `instance` and scaling updates the targets.
`infra/tests/test_core_reliability.py` and `infra/tests/test_monitoring_configuration.py` check
the scrapes during a 2 → 1 → 2 replica cycle.

## Alerts

Prometheus loads `infra/prometheus/alerts.yml`; see pending and firing rules at
<http://localhost:9090/alerts>. No Alertmanager or notification channel is configured.

| Alert | Fires when | What to check |
|---|---|---|
| `AnalysisDeadLettered` | an analysis failed through a DLQ in the last 5 min | the original message and service logs ([runbook](reliability.md#operator-runbook-dlq-and-quarantine)) |
| `AnalysisStuckRecovered` | stuck recovery failed an analysis in the last 5 min | detector health, dispatch and per-source progress |
| `AnalysisDeadLetterBacklog` | DLQ or quarantine messages wait for 2 min | the [runbook](reliability.md#operator-runbook-dlq-and-quarantine) |
| `ScrapeTargetDown` | a scrape fails for 2 min (`up == 0`, not necessarily a stopped process); detectors excluded | the target's container and logs |
| `DetectorUnavailable` | for 2 min `analysis.video`/`analysis.audio` has ready messages and its detector has no healthy scrape target or zero queue consumers | detector health and AMQP connection (bad credentials, `PRECONDITION_FAILED`) |

`DetectorUnavailable` matches the detector job to its queue, so one detector cannot hide the
other's outage, and it stays silent without the `ml` profile while the queues are empty. A missing
consumer metric is not read as zero. Recreate Prometheus after changing rules or scrape config.
`python3 infra/tests/test_observability_configuration.py` validates the dashboards and runs
`promtool check config` and `promtool test rules` against the rule fixtures.

## Traces

`trace_id`/`span_id` always reach the logs. With export on, spans go to Tempo (`tempo:4318`) and
carry `analysis.id` / `analysis.type`. Grafana links both ways: a Loki `trace_id` opens the Tempo
trace and a Tempo trace opens its logs. Tempo 3 runs monolithic with local storage in the
`tempo-data` volume (`infra/tempo/tempo.yaml`); `python3 infra/tests/test_tempo.py` checks
ingestion, retention and trace lookup after a restart.

## Decisions

- **Native ECS logging**, not `logstash-logback-encoder`.
- **Grafana Alloy**, not the Loki Docker driver (a host plugin) or Promtail (EOL 2026-03).
- **Dev trade-offs:** Prometheus, Loki, Tempo and Alloy run as root with named volumes, and Alloy
  mounts the Docker socket with `:ro`, which does not restrict Docker API writes. A compromised
  Alloy has host-equivalent access; see [security](security.md#least-privilege-between-components). Loki, Tempo and Alloy have no healthcheck (distroless images),
  so dependents wait on `service_started`.

## Local verification

1. `docker compose ps`: Prometheus and Grafana `healthy`, Loki, Tempo and Alloy running.
2. Grafana has the Prometheus, Loki and Tempo datasources and the business dashboard loads.
3. `{service=~".+"}` in Loki returns container logs.
4. Prometheus targets for the Java services are `up`; after an analysis the dashboard shows real
   values.
5. With export on, a request produces a Tempo trace reachable from its log line and back.

The business dashboard includes a **File lifecycle** panel with purged uploads and reclaimed
orphan objects over the last hour, summed across file-service replicas. Percentile queries
filter groups whose observation rate is zero, so idle histograms use the panel empty-value
message instead of retaining a NaN series.
