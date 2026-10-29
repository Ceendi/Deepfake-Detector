# DeepfakeDetector — setup and troubleshooting

Everything runs in Docker Compose. Run the published images to use the application, or build
from your checkout and add the Vite dev server to work on the code.

## Requirements

- **Docker Engine 24+** with Compose v2 (`docker compose version`).
- **RAM:** about 8 GB for `core` + `auth`, 14–16 GB with `ml` (each detector may use up to 3 GB).
- **Disk:** about 10–12 GB for the images, plus ~1.6 GB of models.
- **Node.js 22 LTS** (or 20.19+) and npm, only for the frontend dev server.
- Free host ports: `4173` (web UI), `5432, 5672, 6379, 8080, 8180, 8333, 15672, 15692`,
  `5173` for the dev server and, with `monitoring`, `3000, 3100, 3200, 9090, 12345`.

### Windows — WSL2 memory

WSL2 gives Docker only 50% of host RAM by default, which on a 16 GB laptop leads to OOM kills.
Create or edit `%UserProfile%\.wslconfig`:

```ini
[wsl2]
memory=14GB
```

Run `wsl --shutdown` in PowerShell and restart Docker Desktop. On a 16 GB machine keep
`FILE_SERVICE_REPLICAS=1` and leave out `monitoring`. The container memory limits of
`core` + `auth` + `ml` then add up to 14.375 GiB, but they are ceilings: during analyses the
stack used about 6.6 GiB.

## Run the published images

```bash
cp .env.example .env
docker compose up -d
```

`COMPOSE_PROFILES=core,auth,ml` in `.env` selects the profiles ([what they start](README.md#compose-profiles)).
Compose pulls the images from `ghcr.io/ceendi/deepfake-detector` (`latest` = the last green
`main`). Open **<http://localhost:4173>** and register (the default realm has no users). The first
start downloads the images and the models and takes a few minutes; files are analysed once both
detectors are `healthy` in `docker compose ps`.

- **Updating:** `docker compose pull`, then `docker compose up -d`. A plain `up` reuses the images
  already on the machine. Before upgrading an existing PostgreSQL volume to separate orchestrator
  credentials, follow the [data-preserving role migration](#existing-postgresql-volume-role-migration).
  Add `EUREKA_USER/PASSWORD` and an independent random `EUREKA_CSRF_TOKEN` to `.env`. Remove an old explicit
  `JWT_ALLOWED_CLIENTS` override or include `deepfake-cli`; otherwise CLI login succeeds but API
  calls return 401. With no override, Compose tracks the current supported clients automatically.
- **Pinning a version:** `DEEPFAKE_IMAGE_TAG=sha-<7-character commit>` in `.env` runs the images
  built from that commit of `main`.
- **Monitoring:** `COMPOSE_PROFILES=core,auth,ml,monitoring` (Grafana at <http://localhost:3000>,
  see [observability](docs/observability.md)). Dropping `ml` makes the stack lighter, but files are
  then not analysed.

## Build from source (development)

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
docker compose up -d --build
```

`--build` builds every image from your checkout instead of pulling it. The first build needs
internet and takes up to ~15 minutes: `video-detector` compiles `insightface` and downloads the
`buffalo_l` face-detector pack. The `auth` profile builds the Keycloak login theme from
`frontend/` inside its image, so the host needs no Java or Maven; after changing the theme run
`docker compose up -d --build keycloak`. To start only some profiles, list them explicitly, e.g.
`docker compose --profile core --profile auth up -d --build`.

### Frontend dev server

```bash
cd frontend
npm ci
npm run dev
```

The app runs at **<http://localhost:5173>** with hot reload. The dev server proxies `/api` to the
gateway (`localhost:8080`); login goes directly to Keycloak (`localhost:8180`). Both origins are
allowed in the realm. Without `frontend/.env` the app fails with "Brak zmiennej środowiskowej".
The `frontend` container keeps serving the production build of your checkout on port 4173.

## Configuration

The `.env.example` defaults are dev-only and work out of the box.

- **Test users:** `KEYCLOAK_REALM_CONFIG=dev/realm-export.json` in `.env`, set before starting
  `auth`, imports `alice` / `bob` (password `Test1234!@`) and the load-test client. Local use only;
  see [Keycloak configuration](infra/keycloak/README.md).
- **Changing passwords:** database and RabbitMQ administrator credentials are applied only to empty
  volumes; on existing data change them with the service's own tools, then update `.env` and
  recreate the clients (`down -v` would delete the data). The `orchestrator`/`detector` broker
  passwords are re-applied by `rabbitmq-init` on every `docker compose up` with all profiles you
  use, see [broker users](docs/reliability.md#broker-users-and-internal-storage-apis).
  Keycloak's `KC_BOOTSTRAP_ADMIN_*` and Grafana's `GF_SECURITY_ADMIN_PASSWORD` also apply only at
  first initialization. On an existing volume, change the admin password in the respective admin
  console (Keycloak: the admin user's Credentials; Grafana: the admin profile's password), then
  update `.env`. Changing `.env` alone does not rotate either stored password. For Keycloak this
  makes `keycloak-config-cli` fail with 401 and can block frontend startup; synchronize the stored
  password before retrying realm configuration. Do not delete either volume to fix a password.
- **Ports:** override a busy host port in `.env` (`KEYCLOAK_PORT`, `POSTGRES_PORT`, … see
  `.env.example`). With another `KEYCLOAK_PORT` also set `VITE_KEYCLOAK_URL` in `frontend/.env`
  and, for Java services started from an IDE, `JWT_ISSUER_URI`; the containers follow
  `KEYCLOAK_PORT` by themselves.

Further Compose details (Eureka, issuer URLs, S3 and broker credentials, patched images):
[local backend configuration](docs/local-backend.md).

### Existing PostgreSQL volume role migration

This is an operator procedure for a planned upgrade; do not run it automatically on a running
developer application. It preserves the `pgdata` volume and all application rows. No Flyway
migration is changed. The old cluster administrator remains available for rollback and backups.
Commands below use **Bash (WSL/Linux/Git Bash)** from the repository root, with the existing `.env`.
Do not use legacy PowerShell binary redirection for database dumps.
Compose refuses to parse `docker-compose.yml` until `.env` sets the variables from step 3, so add
them before step 1; running containers keep their old environment until recreated.

1. Confirm the target before any changes:

   ```bash
   docker compose -p deepfake-detector ps
   docker compose -p deepfake-detector exec -T postgres sh -c \
     'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT current_database(), current_user"'
   ```

   Record the deployed image tag and database names. Keep the existing `POSTGRES_USER`,
   `POSTGRES_PASSWORD`, `POSTGRES_DB` and file-service settings. Never run `down -v`, volume removal,
   `initdb`, `DROP OWNED` or a cluster-wide ownership reassignment on this data.

2. Back up both service databases and role definitions to a private directory. These commands
   only read the running database; dumps include private data and global role password hashes.

   ```bash
   umask 077
   mkdir -p backups/pre-orchestrator-role
   docker compose -p deepfake-detector exec -T postgres sh -c \
     'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
     > backups/pre-orchestrator-role/orchestrator.dump
   docker compose -p deepfake-detector exec -T postgres sh -c \
     'pg_dump -U "$POSTGRES_USER" -d "$FILE_SERVICE_DB_NAME" -Fc' \
     > backups/pre-orchestrator-role/fileservice.dump
   docker compose -p deepfake-detector exec -T postgres sh -c \
     'pg_dumpall -U "$POSTGRES_USER" --globals-only' \
     > backups/pre-orchestrator-role/globals.sql
   docker compose -p deepfake-detector exec -T postgres pg_restore --list \
     < backups/pre-orchestrator-role/orchestrator.dump
   docker compose -p deepfake-detector exec -T postgres pg_restore --list \
     < backups/pre-orchestrator-role/fileservice.dump
   ```

   Require exit code 0 for every command, nonempty dumps and a successful restore rehearsal in
   an isolated PostgreSQL instance before proceeding. Keep backups outside version control;
   `backups/` is git-ignored.

3. Add a distinct `ORCHESTRATOR_DB_USER=orchestrator` and a strong
   `ORCHESTRATOR_DB_PASSWORD` to `.env`; add URL-safe `EUREKA_USER/PASSWORD` and an independent
   random `EUREKA_CSRF_TOKEN` for the upgrade. All three Java clients send that secret in a CSRF
   header; CSRF protection remains enabled on the registry API.
   Existing containers retain their old environment, so this alone changes no running service.
   Stream the reviewed migration script to Bash in the running PostgreSQL container; no file copy
   to its read-only root filesystem is needed:

   ```bash
   read -r -p 'New orchestrator database role: ' ORCHESTRATOR_DB_USER
   read -r -s -p 'New database password (same as .env): ' ORCHESTRATOR_DB_PASSWORD; echo
   export ORCHESTRATOR_DB_USER ORCHESTRATOR_DB_PASSWORD
   docker compose -p deepfake-detector exec -T \
     -e ORCHESTRATOR_DB_USER -e ORCHESTRATOR_DB_PASSWORD \
     postgres bash -s < infra/postgres/init/02-init-orchestrator-db.sh
   unset ORCHESTRATOR_DB_PASSWORD
   ```

   The script creates or updates the application role and changes ownership of only the selected
   database, its public schema and administrator-owned application relations. It never uses
   `REASSIGN OWNED`, which also transfers shared objects such as other databases. All changes run
   in one transaction with a 5-second lock timeout: on contention or any SQL error it rolls back.
   Retry in a quiet window if necessary. Running it twice with identical values is safe. It refuses
   admin/file-service role collisions and existing privileged roles/memberships. Custom objects
   outside `public`, functions and third-party-owned objects need separate operator review.

4. Verify role flags and password login over TCP before deploying new clients:

   ```bash
   docker compose -p deepfake-detector exec -T postgres sh -c \
     'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT rolname, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls FROM pg_roles WHERE rolname = '\''orchestrator'\''"'
   docker compose -p deepfake-detector exec postgres sh -c \
     'psql -X -h 127.0.0.1 -U orchestrator -d "$POSTGRES_DB" -W -c "SELECT count(*) FROM analysis; SELECT count(*) FROM flyway_schema_history"'
   ```

   Substitute a custom role name if used. All five privilege flags must be false. Compare analysis
   and Flyway history counts with the pre-upgrade database; confirm file-service data and the
   owners of other databases are unchanged. The new role must not be able to read `file_metadata`
   in the file-service database. The old application keeps working as administrator until rollout.

5. Only in the agreed maintenance window, deploy the matching images/configuration and recreate
   PostgreSQL (to refresh its environment, retaining `pgdata`) plus Eureka and its Java clients:
   `docker compose -p deepfake-detector --profile core up -d postgres eureka-server gateway file-service orchestrator`.
   Check health, Flyway validation, registration, analysis history and a normal upload/analysis.
   No `down` or volume deletion is needed. To roll back application images, keep the new role
   and data: the original administrator still has access, so ownership reversal is unnecessary.
   A password correction reruns the same script with the intended password. Restore backups only
   into an isolated instance first; never overwrite the live volume as a troubleshooting step.

## Models

The weights are not in the repository. Each detector downloads them on its first start from a
public Hugging Face repository into a Docker volume (`video-models`, `audio-models`):

| Detector | Hugging Face repository | Pinned in |
|---|---|---|
| `video-detector` | [Ceendi/deepfake-video-detector](https://huggingface.co/Ceendi/deepfake-video-detector) | `video-detector/models.toml` |
| `audio-detector` | [Ceendi/deepfake-audio-detector](https://huggingface.co/Ceendi/deepfake-audio-detector) | `audio-detector/models.toml` |

`models.toml` pins one Hugging Face commit (never a branch) and the SHA-256 of every file, so a
given commit of this repository always runs the same weights. The first start downloads ~1.6 GB
(video ~0.3 GB, audio ~1.4 GB); later starts only verify the checksums and work offline. A file
with a wrong checksum is downloaded again. Outside Docker (`uv run` in a detector directory) the
files go to `training/checkpoints/`.

**Changing a model:** upload the new files to the Hugging Face repository (`hf upload`, never
rewrite its history: older commits still point at the old revision). In the same pull request set
`revision` in `models.toml` to the new commit and update the checksums (`sha256sum <file>`).

## Checking the stack

```bash
docker compose ps
```

Services reach `healthy` within about a minute; on the first start the detectors need a few more
minutes for the models. A detector is `healthy` only after its models loaded.

| Service | URL | Login |
|---|---|---|
| App (container) | <http://localhost:4173> | an account created in the app |
| App (dev server) | <http://localhost:5173> | the same accounts |
| Keycloak admin console | <http://localhost:8180> | `admin` / `changeme_dev` |
| RabbitMQ UI | <http://localhost:15672> | `deepfake` / `changeme_dev` (administrator) |
| Grafana (`monitoring`) | <http://localhost:3000> | `admin` / `admin` |

The passwords are dev-only. All published ports are bound to `127.0.0.1`. Eureka publishes no
port ([service discovery](docs/local-backend.md#service-discovery-boundary)).

## Stopping

```bash
docker compose down          # stops, keeps the data
docker compose down -v       # stops and deletes all data
```

## Troubleshooting

- **Detector `unhealthy`, analyses fail with `MODEL_UNAVAILABLE`:** the model download failed
  (`model_file_download_failed`: no internet or Hugging Face unavailable; 3 retries) or a file
  does not match `models.toml` (`model_file_checksum_mismatch`). Check
  `docker compose logs audio-detector video-detector | grep -iE "model_file|load|error"`, fix the
  cause and run `docker compose restart video-detector audio-detector`.
- **OOM kills or containers restarting:** not enough memory for Docker, see
  [WSL2 memory](#windows--wsl2-memory).
- **Frontend: "Brak zmiennej środowiskowej: VITE_..."** → `frontend/.env` is missing.
- **The first `--build` of `video-detector` takes long** → expected (`insightface` compilation);
  later builds use the cache.
- **Port already in use** → stop the process holding it or override the port in `.env`.
