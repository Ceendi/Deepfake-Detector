# DeepfakeDetector

A web application for detecting deepfakes in video and audio. Upload a file, follow the analysis
live and get a FAKE/REAL verdict with its confidence, Grad-CAM heatmaps and a PDF report. Built
with React, Spring Boot microservices and Python ML detectors, Keycloak login and an asynchronous
RabbitMQ pipeline.

## Quick start

With Docker Engine 24+ and Compose v2, run the images CI publishes to GHCR (nothing is built):

```bash
cp .env.example .env
docker compose up -d
```

Open **[http://localhost:4173](http://localhost:4173)** and create an account. The first start
downloads the images and the detectors' models (~1.6 GB) and takes a few minutes; files are
analysed once `docker compose ps` shows both detectors `healthy`. Memory requirements, test users,
updates and version pinning are in [SETUP.md](SETUP.md).

## Development

Build the images from your checkout and run the frontend dev server (Node.js 22 LTS) with hot
reload:

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
docker compose up -d --build

cd frontend
npm ci
npm run dev
```

The dev server runs on **[http://localhost:5173](http://localhost:5173)**. Tests and linters:
[running the checks locally](docs/ci-cd.md#running-the-same-checks-locally).

## Compose profiles

`COMPOSE_PROFILES` in `.env` selects what a plain `docker compose up` starts (default
`core,auth,ml`); an explicit `--profile` replaces that list.

| Profile | Starts |
| --- | --- |
| `core` | gateway, orchestrator, file-service, Eureka, PostgreSQL, Redis, RabbitMQ, SeaweedFS |
| `auth` | Keycloak and the web UI container (port 4173) |
| `ml` | video and audio detectors (download their models on first start) |
| `monitoring` | Grafana, Prometheus, Loki, Tempo, Alloy |

## Architecture

```text
Frontend ──► Gateway ──► File Service ──► SeaweedFS (S3)
                    └──► Orchestrator ──► PostgreSQL + Redis
                                  │
                                  ├─AMQP──► RabbitMQ ──► Video Detector
                                  │             ▲    └─► Audio Detector
                                  │             └─ progress + results ─┘
                                  └─SSE──► Frontend
```

The gateway listens on `http://localhost:8080` and routes to services found through Eureka.

## Documentation

- [Setup, models and troubleshooting](SETUP.md)
- [Local backend configuration](docs/local-backend.md)
- [API, message, storage and metric contracts](docs/contracts/)
- [Reliability](docs/reliability.md), [security](docs/security.md),
  [monitoring](docs/observability.md), [CI/CD](docs/ci-cd.md)
- [Architecture decisions](docs/adr/) and [measurement reports](docs/reports/)
- [Keycloak configuration](infra/keycloak/README.md), [Infisical secrets](infra/INFISICAL.md)

## Stopping

```bash
docker compose down      # keeps the data; add -v to delete it
```

Stop the frontend dev server with `Ctrl+C`.
