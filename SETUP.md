# DeepfakeDetector — setup & run guide

Web application for video/audio deepfake detection. The backend is a set of
microservices (Spring Boot + Python ML services) run via Docker Compose; the
frontend is a React (Vite) app run locally.

Bringing it up takes **4 steps**: `.env` files → download the models → backend
(Docker) → frontend (npm). The ML models are not in the repository (they are
multi-GB) — you download them separately and drop them into the project **before**
building the Docker images.

---

## Requirements

- **Docker Engine 24+** with Compose v2 (`docker compose version`)
- **Node.js 20+** and `npm` (for the frontend)
- **RAM:** ~14–16 GB for the full set (`core` + `auth` + `ml` profiles; the detectors alone are 2×3 GB)
- **Disk:** ~10–12 GB for the Docker images
- **Internet for the first `--build`** — the `video-detector` image compiles `insightface`
  and downloads the `buffalo_l` face-detector pack; the first build takes several to ~15 minutes.
- Free host ports: `5432, 5672, 6379, 8080, 8180, 8333, 15672` and `5173` (frontend).

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

Nothing needs editing — the defaults are configured for local development.

---

## Step 2 — download and place the models

### Where to download

The models are shared on SharePoint (folder **`MODELE-AUDIO-DETECTOR`**):

<https://tulodz-my.sharepoint.com/shared?id=%2Fpersonal%2F247770%5Fedu%5Fp%5Flodz%5Fpl%2FDocuments%2FMODELE%2DAUDIO%2DDETECTOR&listurl=%2Fpersonal%2F247770%5Fedu%5Fp%5Flodz%5Fpl%2FDocuments>

### Where to put them (folder mapping)

The repo does **not** contain the `checkpoints/` directories — create them (or use the
ready-made directories that already contain a `PLACE_MODELS_HERE.md` file marking the exact spot).
Copy the contents **keeping the exact file names**:

| From SharePoint | Into the project |
|---|---|
| `Audio-Detector/checkpoints/w2v2/` | `audio-detector/training/checkpoints/w2v2/` |
| `Audio-Detector/checkpoints/mel_resnet/` | `audio-detector/training/checkpoints/mel_resnet/` |
| `Video-Detector/checkpoints/effnet_lstm/` | `video-detector/training/checkpoints/effnet_lstm/` |

### Exact files the code requires

The names are hardcoded in the inference code (`*/src/inference.py`) — they must match **character-for-character**:

**Audio — `audio-detector/training/checkpoints/`:**
```
w2v2/w2v2.onnx
w2v2/w2v2.onnx.data                                 # weights sidecar (if present in SharePoint, upload it together with the .onnx)
mel_resnet/mel_model-epoch=49-val_eer=0.0816.ckpt   # the name with the "=" signs must stay unchanged
```

**Video — `video-detector/training/checkpoints/effnet_lstm/`:**
```
backbone.onnx
backbone.onnx.data   # if present in SharePoint (sidecar), upload it together
temporal.onnx
last.ckpt
```
The `video-epoch*.ckpt` files and the `grid/` subfolder (if any) are not needed — skip them.

> ⚠️ **Most common mistake:** a renamed/incomplete file name, or skipping the `*.onnx.data` sidecar.
> Then the container starts but every analysis ends with status `FAILED`. Copy **all** files from
> the given SharePoint folder without renaming them.

### Verify placement (run from the project root)

```bash
ls -la audio-detector/training/checkpoints/w2v2/w2v2.onnx \
       "audio-detector/training/checkpoints/mel_resnet/mel_model-epoch=49-val_eer=0.0816.ckpt" \
       video-detector/training/checkpoints/effnet_lstm/backbone.onnx \
       video-detector/training/checkpoints/effnet_lstm/temporal.onnx \
       video-detector/training/checkpoints/effnet_lstm/last.ckpt
```
Each line should show an existing file (not "No such file or directory").

---

## Step 3 — backend (Docker Compose)

> The models are **baked into the images at build time**, so Step 2 must be finished before `--build`.
> If you add/fix models after building the images, you must rebuild (see "Troubleshooting").

From the project root:

```bash
docker compose --profile core --profile auth --profile ml --profile monitoring up --build
```

Profiles:

| Profile | What it starts | Needed for |
|---|---|---|
| `core` | eureka, gateway, orchestrator, file-service + postgres, redis, rabbitmq, seaweedfs | the backend to work (required) |
| `auth` | Keycloak (login) + its database | login / registration in the app |
| `ml` | `video-detector`, `audio-detector` | actual file analysis (needs the models from Step 2) |
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
Services should reach `healthy` within ~60 s. To confirm the models actually loaded, check the logs
(no `Failed to load ...` / checkpoint-load errors):
```bash
docker compose logs audio-detector video-detector | grep -iE "ready|load|error"
```

---

## Step 4 — frontend (Vite)

In a separate terminal, inside `frontend/`:

```bash
cd frontend
npm install
npm run dev
```

The app will be at **<http://localhost:5173>**. The frontend proxies `/api` to the gateway
(`localhost:8080`) and login to Keycloak (`localhost:8180`) — both addresses are whitelisted in
the realm, so registration/login work out of the box.

You can create an account (open registration) and upload a video or audio file for analysis.

---

## Useful addresses (dev)

| Service | URL | Login |
|---|---|---|
| App (frontend) | <http://localhost:5173> | create an account in the app |
| Keycloak (admin console) | <http://localhost:8180> | `admin` / `changeme_dev` |
| RabbitMQ UI | <http://localhost:15672> | `deepfake` / `changeme_dev` |
| Grafana (`monitoring` profile) | <http://localhost:3000> | `admin` / `admin` |

All published ports are bound to `127.0.0.1` (reachable only from this machine).
Eureka does not publish port `8761`; its dashboard and registration API are internal
to the trusted Compose network. The Gateway remains at `http://localhost:8080`.
The passwords are dev-only. See [discovery isolation and verification](README.md#architecture)
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

- **Analysis ends `FAILED` but the detector container started** → missing models or a wrong file name.
  Check Step 2 (especially exact names and the `*.onnx.data` sidecars), then rebuild the detectors:
  ```bash
  docker compose --profile ml up -d --build video-detector audio-detector
  ```
- **I added the models after `--build` and nothing changed** → the images still have the old (empty) `training/`.
  Rebuild as above (`up -d --build ...`).
- **Frontend: "Brak zmiennej środowiskowej: VITE_..."** → missing `frontend/.env` (Step 1, second command).
- **The first `--build` hangs for a long time on `video-detector`** → that is normal: compiling `insightface`
  and downloading the `buffalo_l` pack. It needs internet; later builds use the cache.
- **Port already in use** → stop the process holding the port, or override it via a variable in `.env`
  (e.g. `KEYCLOAK_PORT`, `POSTGRES_PORT` — see `.env.example`).
