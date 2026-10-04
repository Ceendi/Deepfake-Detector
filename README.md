# DeepfakeDetector

Web application for video/audio deepfake detection. Microservices (Spring Boot + Python ML) behind an API gateway, async pipeline on RabbitMQ, OIDC via Keycloak.

> **Setting the project up from scratch (e.g. instructor review)?** A full step-by-step
> guide — `.env` files, downloading and placing the ML models, running the backend and
> frontend — is in **[`SETUP.md`](SETUP.md)**. The README below is a condensed developer overview.

## Requirements

- Docker Engine 24+ with Compose v2
- Node.js 20+ and npm (frontend)
- ~8 GB RAM for core + auth; ~14–16 GB with the `ml` profile (two 3 GB detectors)
- Free ports: 5432, 5672, 6379, 8080, 8180, 8333, 15672 (backend) and 5173 (frontend)

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
(upload → analysis → progress → verdict) end to end. The `ml` profile needs the
model checkpoints in place first — see [Models](#models-checkpoints) below.

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
| `ml`         | video-detector, audio-detector (real inference — **requires** the checkpoints, see [Models](#models-checkpoints)) | running the analysis pipeline  |
| `monitoring` | prometheus, loki, tempo, grafana, alloy                                                         | metrics + logs + traces (D2/D3)|

## Models (checkpoints)

The ML detectors run **real inference** and load trained checkpoints at startup.
The checkpoints are not in the repo (multi-GB; ignored by `.gitignore`) — download
them and place them under each detector's `training/checkpoints/` **before** building
the images, because they are baked into the image at build time
(`COPY training ./training` in each Dockerfile; whitelisted in `.dockerignore`).
Without them the `ml` containers start but every analysis ends `FAILED`.

Download (SharePoint folder `MODELE-AUDIO-DETECTOR`):
<https://tulodz-my.sharepoint.com/shared?id=%2Fpersonal%2F247770%5Fedu%5Fp%5Flodz%5Fpl%2FDocuments%2FMODELE%2DAUDIO%2DDETECTOR&listurl=%2Fpersonal%2F247770%5Fedu%5Fp%5Flodz%5Fpl%2FDocuments>

Exact paths and filenames expected by the code (`*/src/inference.py`):

```
audio-detector/training/checkpoints/w2v2/w2v2.onnx
audio-detector/training/checkpoints/w2v2/w2v2.onnx.data            # if present (external-data sidecar)
audio-detector/training/checkpoints/mel_resnet/mel_model-epoch=49-val_eer=0.0816.ckpt
video-detector/training/checkpoints/effnet_lstm/backbone.onnx
video-detector/training/checkpoints/effnet_lstm/temporal.onnx
video-detector/training/checkpoints/effnet_lstm/last.ckpt
```

Filenames must match exactly. Full walkthrough (including a verification command and
how to rebuild after adding models): **[`SETUP.md`](SETUP.md)**.

## Frontend (dev)

The React/Vite frontend runs locally, not in Compose. It proxies `/api` to the gateway
(`localhost:8080`) and redirects login to Keycloak (`localhost:8180`), so start the backend
(`core` + `auth`) first.

```bash
cd frontend
cp .env.example .env   # required — the app throws on missing VITE_* vars
npm install
npm run dev            # http://localhost:5173
```

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
Eureka has no published host port: its dashboard and registration API are available
only at `http://eureka-server:8761` inside the trusted Compose network. Services
continue to use `http://eureka-server:8761/eureka/`; no registry credentials are
needed for this local development network. Do not attach untrusted containers to
that network or publish the registry in an override without adding authentication.
The Gateway host port is bound to `127.0.0.1:8080`.

After updating an existing stack, apply the port changes with:

```bash
docker compose --profile core up -d --force-recreate eureka-server gateway
```

This preserves named volumes. A plain container restart does not update port bindings.
For an internal registry check without opening a host port:

```bash
docker compose exec gateway wget -qO- http://eureka-server:8761/eureka/apps
```

The isolated regression builds the Java services, uses fresh project-scoped storage
and ephemeral application ports on loopback, checks registration and authenticated
gateway routing, then repeats after a restart:

```bash
python3 infra/tests/test_discovery.py
```

Its external-network container checks the host-facing TCP boundary through the host's
non-loopback interface, with a disposable TCP echo listener on an ephemeral wildcard
port as a positive control. This is not a second physical host; also verify port 8761 and
the gateway port from another LAN host when one is available.
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
