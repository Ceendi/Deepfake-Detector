# DeepfakeDetector — setup & run guide

Web application for video/audio deepfake detection. The backend is a set of
microservices (Spring Boot + Python ML services) run via Docker Compose; the
frontend is a React (Vite) app run locally.

Bringing it up takes **3 steps**: `.env` files → backend (Docker) → frontend (npm).
The ML models are not in the repository; the detectors download pinned versions
from Hugging Face on their first start (Step 2 explains how).

---

## Requirements

- **Docker Engine 24+** with Compose v2 (`docker compose version`)
- **Node.js 22 LTS** (or 20.19+, required by Vite) and `npm` (for the frontend)
- **RAM:** ~14–16 GB for the full set (`core` + `auth` + `ml` profiles; the detectors alone are 2×3 GB)
- **Disk:** ~10–12 GB for the Docker images
- **Internet for the first `--build`** — the `video-detector` image compiles `insightface`
  and downloads the `buffalo_l` face-detector pack; the first build takes several to ~15 minutes.
- Free host ports: `5432, 5672, 6379, 8080, 8180, 8333, 15672, 15692` and `5173` (frontend);
  with `monitoring` also `3000, 3100, 3200, 9090, 12345`.

### Windows — WSL2 memory

WSL2 defaults to 50% of host RAM, so a 16 GB laptop normally gives Docker only
8 GB. This can cause OOM kills even when the model files are correct. Create or
edit `%UserProfile%\.wslconfig` on Windows (outside the Linux filesystem):

```ini
[wsl2]
memory=14GB
```

Apply with `wsl --shutdown` in PowerShell, then restart Docker Desktop. This is a
ceiling shared by WSL2 distributions; leave room for Windows and close heavy apps.
On a 16 GB machine keep `FILE_SERVICE_REPLICAS=1` in the root `.env` and omit
`monitoring`. Start with `core` + `auth`, then add `ml`.

---

## Step 1 — `.env` files

The app reads secrets and config from `.env` files. The repo only ships `.env.example`
templates — turn them into `.env` (the defaults are dev-only and work out of the box).

You need **two** files:

```bash
# 1) in the project root (for Docker Compose)
cp .env.example .env

# 2) in frontend/ (Vite; without it the frontend throws "Brak zmiennej środowiskowej")
cp frontend/.env.example frontend/.env
```

The defaults import the realm without test accounts. Register a user through
the app. For local smoke tests with `alice`/`bob` or JMeter, add
`KEYCLOAK_REALM_CONFIG=dev/realm-export.json` to the root `.env` before starting
`auth`; see [Keycloak configuration](infra/keycloak/README.md). Keep this option
unset in deployments.

Database credentials and RabbitMQ administrator bootstrap credentials initialize only on empty
volumes. Editing passwords in `.env` does not rotate accounts in existing data:
change the stored credentials through the service's administration tools, then
update and recreate the clients. Keep persistent data; `down -v` deletes it.
Application broker passwords are reconciled on every `rabbitmq-init` run; update
`.env` and recreate all affected clients (including the `ml` profile when used).
See [broker users](docs/reliability.md#broker-users-and-internal-storage-apis).

The `auth` profile builds the Keycloak image with the login theme from `frontend/` sources
(`infra/keycloak/Dockerfile`), so no JAR is committed and the host needs no Java or Maven.
The first build takes a few minutes. After changing the theme, rebuild it with
`docker compose --profile auth up -d --build keycloak`.

Keycloak realm policy is synchronized separately by `keycloak-config-cli`.

If you override `KEYCLOAK_PORT`, also set `VITE_KEYCLOAK_URL` in `frontend/.env`
(e.g. `http://localhost:8280`). For Java services started from an IDE, update
`JWT_ISSUER_URI` too; Compose derives its issuer from `KEYCLOAK_PORT`.

---

## Step 2 — models (downloaded automatically)

Nothing to do by hand. The model weights are not in the repository; they live in public Hugging Face
repositories and each detector downloads them on its first start:

| Detector | Hugging Face repository | Pinned in |
|---|---|---|
| `video-detector` | [Ceendi/deepfake-video-detector](https://huggingface.co/Ceendi/deepfake-video-detector) | `video-detector/models.toml` |
| `audio-detector` | [Ceendi/deepfake-audio-detector](https://huggingface.co/Ceendi/deepfake-audio-detector) | `audio-detector/models.toml` |

- `models.toml` pins one Hugging Face **commit** and the SHA-256 of every file, so a given commit of this
  repository (and a given image tag) always runs the same weights. A branch such as `main` is rejected.
- The first start of the `ml` profile downloads ~1.6 GB (video ~0.3 GB, audio ~1.4 GB) into the Docker
  volumes `video-models` and `audio-models`. Later starts only verify the checksums and work offline.
- A file with a wrong checksum is downloaded again; if the download keeps failing (3 retries) the detector
  stays `unhealthy` and analyses end with `MODEL_UNAVAILABLE`.
- Outside Docker (`uv run` in a detector directory) the files go to `training/checkpoints/` as before;
  already present files with the right checksum are used as they are.

### Changing a model

1. Upload the new files to the Hugging Face repository (`hf upload`); never rewrite its history, because
   older commits of this repository still point at the old revision.
2. In the same pull request, set `revision` in `models.toml` to the new Hugging Face commit and update the
   checksums (`sha256sum <file>`), together with any code change the new weights need.

---

## Step 3 — backend (Docker Compose)

From the project root:

```bash
docker compose --profile core --profile auth --profile ml --profile monitoring up --build
```

Profiles:

| Profile | What it starts | Needed for |
|---|---|---|
| `core` | eureka, gateway, orchestrator, file-service + postgres, redis, rabbitmq, seaweedfs | the backend to work (required) |
| `auth` | Keycloak (login) + its database | login / registration in the app |
| `ml` | `video-detector`, `audio-detector` | actual file analysis (downloads the models on first start) |
| `monitoring` | Prometheus, Loki, Tempo, Grafana, Alloy | metrics/logs view (optional, can be skipped for a lighter start) |

Without the `monitoring` profile (lighter on the machine):
```bash
docker compose --profile core --profile auth --profile ml up --build
```

### Verify

In a separate terminal:
```bash
docker compose ps
```
Services should reach `healthy` within ~60 s; on the first start the detectors need a few more
minutes to download their models. A detector reports `healthy` only after its models loaded; if one
stays `unhealthy`, check its logs for `model_file_*` and `failed_to_load_*_inference`:
```bash
docker compose logs audio-detector video-detector | grep -iE "model_file|ready|load|error"
```

---

## Step 4 — frontend (Vite)

In a separate terminal, inside `frontend/`:

```bash
cd frontend
npm install
npm run dev
```

The app will be at **<http://localhost:5173>**. The dev server proxies `/api` to the gateway
(`localhost:8080`); login goes directly to Keycloak (`localhost:8180`). Both are whitelisted in
the realm, so registration/login work out of the box.

You can create an account (open registration) and upload a video or audio file for analysis.

---

## Useful addresses (dev)

| Service | URL | Login |
|---|---|---|
| App (frontend) | <http://localhost:5173> | create an account in the app |
| Keycloak (admin console) | <http://localhost:8180> | `admin` / `changeme_dev` |
| RabbitMQ UI | <http://localhost:15672> | `deepfake` / `changeme_dev` (administrator; services use the `orchestrator`/`detector` users) |
| Grafana (`monitoring` profile) | <http://localhost:3000> | `admin` / `admin` |

All published ports are bound to `127.0.0.1` (reachable only from this machine).
Eureka does not publish port `8761`; its dashboard and registration API are internal
to the trusted Compose network. The Gateway remains at `http://localhost:8080`.
The passwords are dev-only. See [discovery isolation and verification](docs/local-backend.md#service-discovery-boundary)
for the registry check and the recreation command required when upgrading an existing stack.

---

## Stop / cleanup

```bash
docker compose down          # stops, keeps data (volumes)
docker compose down -v       # stops and wipes all data
```
Stop the frontend with `Ctrl+C` in its terminal.

---

## Troubleshooting

- **Detector `unhealthy`, analyses end `FAILED` with `MODEL_UNAVAILABLE`** → the model download failed
  (`model_file_download_failed`: no internet or Hugging Face unavailable) or a file does not match
  `models.toml` (`model_file_checksum_mismatch`). Fix the cause and restart the detector:
  ```bash
  docker compose --profile ml restart video-detector audio-detector
  ```
- **Frontend: "Brak zmiennej środowiskowej: VITE_..."** → missing `frontend/.env` (Step 1, second command).
- **The first `--build` hangs for a long time on `video-detector`** → that is normal: compiling `insightface`
  and downloading the `buffalo_l` pack. It needs internet; later builds use the cache.
- **Port already in use** → stop the process holding the port, or override it via a variable in `.env`
  (e.g. `KEYCLOAK_PORT`, `POSTGRES_PORT` — see `.env.example`).
