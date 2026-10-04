# DeepfakeDetector

A web application for detecting deepfakes in audio and video. Upload a file,
choose an analysis mode, follow its progress, and view or download the result.

Built with React, Spring Boot and Python ML services, with Keycloak authentication
and an asynchronous RabbitMQ pipeline.

## Getting started

You need Docker Engine 24+ with Compose v2, Node.js 20+ and npm. Allow about 8 GB
of RAM for the backend and authentication, or 14–16 GB with both ML detectors.

**First time here?** Follow [SETUP.md](SETUP.md) for the complete walkthrough,
including model downloads, configuration and troubleshooting.

Start the backend:

```bash
cp .env.example .env
docker compose --profile core --profile auth up -d
```

Start the frontend in another terminal:

```bash
cd frontend
cp .env.example .env
npm install
npm run dev
```

Open **[http://localhost:5173](http://localhost:5173)** and create an account.
The example credentials and published backend ports are for local development.

To run actual analyses, download the model checkpoints as described in
[SETUP.md](SETUP.md#step-2--download-and-place-the-models), then add `--profile ml`
to the backend command. Checkpoints are required before building the detector images.

## Compose profiles

| Profile | Purpose |
| --- | --- |
| `core` | API gateway, application services, discovery, databases, broker and storage |
| `auth` | Keycloak login and account management |
| `ml` | Audio and video detectors; requires model checkpoints |
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
- [Local backend configuration, discovery and upgrades](docs/local-backend.md)
- [API and message contracts](docs/contracts/)
- [Monitoring](docs/observability.md)
- [Keycloak configuration](infra/keycloak/README.md)
- [Infisical secrets setup](infra/INFISICAL.md)

## Stopping the application

Stop the frontend with `Ctrl+C`. Stop the backend while keeping its data:

```bash
docker compose down
```
