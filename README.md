# DeepfakeDetector

A web application for detecting deepfakes in audio and video. Upload a file,
choose an analysis mode, follow its progress, and view or download the result.

Built with React, Spring Boot and Python ML services, with Keycloak authentication
and an asynchronous RabbitMQ pipeline.

## Quick start

With Docker Engine 24+ and Compose v2, two commands run the whole application from the images
CI publishes to GHCR, nothing is built locally:

```bash
cp .env.example .env
docker compose --profile core --profile auth --profile ml up -d
```

Open **[http://localhost:4173](http://localhost:4173)** and create an account. The first start
downloads the images and the detectors' models (~1.6 GB) and takes a few minutes; analyses run
once `docker compose ps` shows the detectors `healthy`. Set `DEEPFAKE_IMAGE_TAG=sha-<commit>` in
`.env` to run a specific build of `main`; see [SETUP.md](SETUP.md#quick-start-published-images-nothing-to-build).

## Getting started (development)

You need Docker Engine 24+ with Compose v2, Node.js 22 LTS (or 20.19+) and npm. Allow about 8 GB
of RAM for the backend and authentication, or 14–16 GB with both ML detectors.
On Windows, WSL2 defaults to 50% of host RAM. On a 16 GB laptop, set
`memory=14GB` in `%UserProfile%\.wslconfig`, run `wsl --shutdown`, and use
`FILE_SERVICE_REPLICAS=1` without the `monitoring` profile. The memory limits then add up to
14.375 GiB, but they are ceilings; during analyses the stack used about 6.6 GiB. See
[Windows memory configuration](SETUP.md#windows--wsl2-memory).

**First time here?** Follow [SETUP.md](SETUP.md) for the complete walkthrough,
including model downloads, configuration and troubleshooting.

Build and start the backend from your checkout (`auth` also builds the Keycloak login theme):

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
docker compose --profile core --profile auth up -d --build
```

Start the frontend in another terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open **[http://localhost:5173](http://localhost:5173)** and create an account.
The example credentials and published backend ports are for local development.

To run actual analyses, add `--profile ml` to the backend command. On their first start the
detectors download the model weights (~1.6 GB) from the Hugging Face commit pinned in each
detector's `models.toml` and verify their SHA-256; see
[SETUP.md](SETUP.md#step-2--models-downloaded-automatically), which also covers changing a model.

## Compose profiles

| Profile | Purpose |
| --- | --- |
| `core` | API gateway, application services, discovery, databases, broker and storage |
| `auth` | Keycloak login and account management, web UI container (port 4173) |
| `ml` | Audio and video detectors; download their models on first start |
| `monitoring` | Grafana, metrics, logs and traces |

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

The gateway is available at `http://localhost:8080`. Services discover each other
through an internal Eureka registry, and the frontend receives progress via SSE.

## Documentation

- [Setup, models and troubleshooting](SETUP.md)
- [Local backend configuration](docs/local-backend.md)
- [API and message contracts](docs/contracts/)
- [Monitoring](docs/observability.md)
- [Backend reliability](docs/reliability.md)
- [Keycloak configuration](infra/keycloak/README.md)
- [Infisical secrets setup](infra/INFISICAL.md)

## Stopping the application

Stop the frontend with `Ctrl+C`. Stop the backend while keeping its data:

```bash
docker compose down
```
