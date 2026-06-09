# DeepfakeDetector

Web application for video/audio deepfake detection. Microservices (Spring Boot + Python ML) behind an API gateway, async pipeline on RabbitMQ, OIDC via Keycloak.

## Requirements

- Docker Engine 24+ with Compose v2
- ~8 GB RAM for the full stack (core + auth)
- Free ports: 5432, 5672, 6379, 8180, 8333, 15672

## Quick start (development)

Two ways to inject secrets — pick one. They produce identical results.

### Option A — Infisical (recommended)

Single source of truth across dev / staging / prod, no `.env` files on
disk. One-time setup in [`infra/INFISICAL.md`](infra/INFISICAL.md)
(account, CLI install, project link). Then:

```bash
infisical run --env=dev -- docker compose --profile core --profile auth up -d
```

### Option B — local `.env` fallback

For contributors who don't want an Infisical account. Same compose, secrets
read from `.env`:

```bash
cp .env.example .env
docker compose --profile core --profile auth up -d
```

`.env` is gitignored. Values in `.env.example` are clearly marked dev-only
and unsafe outside localhost.

Add `--profile ml` to also start the detectors and run a full analysis
(upload → analysis → progress → verdict) end to end.

Add `--profile monitoring` for the observability stack (Grafana at
http://localhost:3000, admin / `GF_SECURITY_ADMIN_PASSWORD`); set
`OTEL_TRACING_EXPORT_ENABLED=true` to ship traces to Tempo. See
[docs/observability.md](docs/observability.md).

### Verify

```bash
docker compose ps
```

All services should report `healthy` within ~60 seconds.

## Profiles

| Profile      | Services                                                                                        | Use when                       |
| ------------ | ----------------------------------------------------------------------------------------------- | ------------------------------ |
| `core`       | eureka, gateway, orchestrator, file-service + postgres, redis, rabbitmq, seaweedfs (+ 2 inits)  | any backend/frontend dev       |
| `auth`       | keycloak + dedicated keycloak-db (Postgres) + realm config one-shot                             | login flow needed              |
| `ml`         | video-detector, audio-detector (dummy inference for now, full pipeline otherwise)               | running the analysis pipeline  |
| `monitoring` | prometheus, loki, tempo, grafana, alloy                                                         | metrics + logs + traces (D2/D3)|

## URLs (dev)

| Service          | URL                    | Credentials                                                         |
| ---------------- | ---------------------- | ------------------------------------------------------------------- |
| RabbitMQ UI      | http://localhost:15672 | `RABBITMQ_USER` / `RABBITMQ_PASSWORD`                               |
| SeaweedFS S3 API | http://localhost:8333  | `S3_FILE_SERVICE_KEY` / `S3_FILE_SERVICE_SECRET` (or `_DETECTOR_*`) |
| Keycloak         | http://localhost:8180  | `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`                        |
| Postgres         | `localhost:5432`       | `POSTGRES_USER` / `POSTGRES_PASSWORD`                               |
| Redis            | `localhost:6379`       | password: `REDIS_PASSWORD`                                          |

> All host port bindings are scoped to `127.0.0.1` — they're reachable from
> your machine but not from your LAN. Dev credentials never leave the laptop.

## Object storage — SeaweedFS

Buckets and IAM identities are documented in
[`docs/contracts/object-storage.md`](docs/contracts/object-storage.md) and
provisioned by `infra/seaweedfs/init.sh` (config render) +
`infra/seaweedfs/bucket-init.sh` (bucket create on first boot).

## Authentication

Keycloak realm `deepfake` is auto-imported from
[`infra/keycloak/realm-export.json`](infra/keycloak/realm-export.json) on
first boot (see [`infra/keycloak/README.md`](infra/keycloak/README.md) for
password policy, brute-force settings, open registration as a deliberate
product decision, and the email-verification setup deferred until SMTP).

`KC_HOSTNAME` is forced to `http://localhost:8180` in dev, so JWTs always
carry the same `iss` claim regardless of which network the request
originated from. Backend services validate that `iss` against
`JWT_ISSUER_URI=http://localhost:8180/realms/deepfake` but fetch the signing
keys from `JWK_SET_URI=http://keycloak:8080/.../certs` over the docker
network — `localhost` inside a container is the container itself, so the key
fetch must target Keycloak directly. The frontend uses the public
`localhost:8180` URL for the login redirect.

## Architecture

```
Frontend ──► Gateway ──► File Service ──► SeaweedFS (S3)
                    └──► Orchestrator ──► PostgreSQL + Redis
                                  │
                                  ├─AMQP──► RabbitMQ ──► Video Detector
                                  │             ▲    └─► Audio Detector
                                  │             └─ progress + results ─┘
                                  └─SSE──► Frontend (progress, verdict)
```

Service discovery is via Eureka; the Gateway routes by `lb://SERVICE-NAME`.
Realtime updates use SSE (`GET /api/analysis/{id}/stream`), not WebSocket.

The async pipeline is built for resilience (D6): manual ack / ack-after-commit,
retry with a dead-letter queue + DLQ consumer, Redis-backed idempotency, stuck-job
recovery, and graceful degradation when Redis is down.

Queue/exchange topology is declared by application code at startup (Spring
AMQP `@Bean` in the Orchestrator, `pika queue_declare` in detectors). The
broker boots empty. Contracts: [`docs/contracts/`](./docs/contracts/).

## Cleanup

```bash
docker compose down                          # stop, keep volumes
docker compose down -v                       # stop and wipe all data
```
