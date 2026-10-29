# CI/CD pipeline

GitHub Actions builds, tests, analyses, scans and publishes the system. This page explains the
flow and where each result can be found. Workflow files are in `.github/workflows/`.

## Flow

```text
pull request ─► CI ──────────────► tests, coverage, SonarCloud Quality Gate, dependency scans
             ├► E2E ─────────────► full core + auth stack in Compose, Playwright in Chromium
             ├► Infrastructure smoke ► restarts, discovery, Keycloak, S3/RabbitMQ least privilege
             ├► Security scan ───► Trivy on every Compose image and on Dockerfile/Compose config
             └► build-push ──────► images are built and scanned, not pushed (only when an image definition changes)

push to main ─► the same workflows
              └► CI passes ─► build-push ─► Trivy ─► GHCR: sha-<commit> + latest + provenance

1st and 15th of the month, or manual ─► E2E real models: published images + real models
```

Every workflow runs on pull requests and on pushes to `main`. Pull request runs that are
superseded by a new push are cancelled; runs on `main` always finish. Path filters skip
workflows that a change cannot affect (for example E2E for a documentation-only change).

Inside CI, a pull request runs only the test jobs its changed files can affect
(`infra/ci/ci_scope.py`, guarded by `infra/tests/test_ci_scope.py`):

| Pull request changes | Runs |
|---|---|
| only `frontend/` | `frontend`; Java services only compile (classes for Sonar) |
| one Java service | that service's `mvn verify`; the other three only compile |
| a detector, `detector-runtime/` or `tests/worker_redelivery/` | detector jobs and the orchestrator tests that start the real detector consumers |
| shared wire fixtures (`orchestrator/src/test/resources/contracts/`) | orchestrator, detectors and frontend |
| only `docs/` or root `*.md` | no test job |
| only `tools/` | no gated job; the always-running `python (tools)` lints and tests it |
| anything else (`docker-compose.yml`, `infra/`, `.github/`, configuration, a new directory) | everything |

Pushes to `main` always run everything, and a failed selection runs everything. Ruff, the
dependency scan and SonarCloud run on every pull request; SonarCloud also runs when a test job was
skipped, cancelled or failed, so a skipped required check never stands in for an analysis.

## Workflows

| Workflow | Trigger | Jobs | Fails when |
|---|---|---|---|
| `ci.yml` (CI) | PR (jobs by changed paths), push | `java (*)` (Maven verify of 4 services), `python (*)` (ruff; unit tests of `tools/`), `Lightweight detector contract (*)`, `ML dependency compatibility (*)`, `Worker process redelivery`, `frontend` (lint, vitest, build, npm audit), `Application dependency vulnerabilities`, `Detector image security and compatibility (*)`, `Test report`, `SonarCloud Quality Gate` | a test or lint fails, the Quality Gate fails, a fixable HIGH/CRITICAL dependency or image vulnerability exists |
| `e2e.yml` (E2E) | PR (app paths), push | `playwright`: `docker compose up --wait` of the core and auth profiles, then Playwright through Keycloak login, upload and analysis | a service is unhealthy or a browser test fails |
| `infra-smoke.yml` | PR (infra paths), push | restart and persistence, Prometheus scraping of every replica, Tempo ingest, Keycloak import, discovery isolation, S3 and RabbitMQ permissions | a running stack does not behave as configured |
| `security-scan.yml` | PR (image paths), push, weekly | Trivy per Compose image (SARIF to Code Scanning), Trivy config scan, SBOM, closing alerts of removed images | an image has a CRITICAL vulnerability without an active owner-approved acceptance |
| `build-push.yml` | after CI on `main`, PR (image paths), weekly rebuild without cache, manual (`main` only) | `image (*)` for 11 images: build, Trivy, push, provenance attestation | an image does not build or has a CRITICAL vulnerability |
| `e2e-ml.yml` (E2E real models) | 1st and 15th of the month 03:00 UTC, manual | `Real-model batch analysis`: published images with the real models, batch CLI on generated media | an image or model does not start, or an analysis ends with an unexpected outcome |

## Tests and reports

| Layer | Tool | Report |
|---|---|---|
| Java services | JUnit 5, Testcontainers (real PostgreSQL, Redis, RabbitMQ, SeaweedFS) | Surefire XML, JaCoCo HTML/XML |
| Detectors and shared runtime | pytest | JUnit XML, coverage XML |
| Detector process redelivery | pytest against real RabbitMQ and Redis | job log |
| Frontend | vitest + Testing Library | JUnit XML, lcov, HTML coverage |
| End to end | Playwright | HTML report, JUnit XML |

All results are uploaded as `test-results-*` and `e2e-report` artifacts (14 days). The `Test report`
job adds a summary of every JUnit file to the run's **Summary** tab, with failed tests
annotated in the pull request. A failing test fails its job; branch protection on `main` makes the
CI, Quality Gate and E2E checks required, so a red pull request cannot be merged.

## Static analysis and Quality Gate

SonarCloud analyses the whole repository as one project: Java, TypeScript, Python, Dockerfiles and
these workflows (`sonar-project.properties`). The `SonarCloud Quality Gate` job runs after the test
jobs, imports their coverage (JaCoCo, lcov, coverage.py) and waits for the Quality Gate result
(`sonar.qualitygate.wait=true`).

The gate is SonarCloud's "Sonar way" on new code: no new bugs or vulnerabilities (rating A), all
new security hotspots reviewed, at least 80 % coverage and at most 3 % duplication on new code.
On a pull request "new code" is the diff against `main`; on `main` it is the last 30 days.
Reviewed exceptions are listed with their reason in `sonar-project.properties`.

Linters run in the same pipeline: ESLint (frontend), ruff (Python), TypeScript type checking.

## Images and versions

After CI succeeds for a push to `main`, `build-push.yml` builds the exact commit that CI verified
and publishes to the GitHub Container Registry:

```text
ghcr.io/ceendi/deepfake-detector/<image>:sha-<7-character commit>
ghcr.io/ceendi/deepfake-detector/<image>:latest
```

Images: `gateway`, `eureka-server`, `orchestrator`, `file-service`, `video-detector`,
`audio-detector`, `keycloak` (with the login theme), `frontend` (static build served by
unprivileged nginx) and the patched infrastructure images `postgres`, `keycloak-config-cli` and
`alloy`. Together they are every image `docker-compose.yml` builds, so a plain
`docker compose up` runs the published images without building anything
(`infra/tests/test_compose_images.py` keeps the two lists in sync). `DEEPFAKE_IMAGE_TAG` in `.env`
selects the tag: `latest` by default, or `sha-<commit>` for a reproducible deployment. The
frontend image reads its Keycloak address from the container environment at start (`/config.js`),
so the same image works with any `KEYCLOAK_PORT`. Each image carries the OCI `revision` label of
its commit. A failed CI run publishes nothing. Pull requests that change an image definition build
and scan all images without pushing. Every Monday (and on a manual run with `no-cache`) the
workflow rebuilds `main` without the layer cache, so the `apk upgrade` / `apt-get` layers take
the distribution fixes released since the last build; the images go through the same Trivy gate
and are published as `sha-<commit>` and `latest`.

Each image is built and loaded locally first, scanned with Trivy (a CRITICAL vulnerability stops
it, HIGH findings are listed in the job summary), and only then pushed from the same build cache.
Every pushed image gets a signed SLSA build provenance attestation (`actions/attest`, Sigstore),
stored next to the image in GHCR. It links the image digest to the commit and the workflow run that
built it:

```bash
gh attestation verify oci://ghcr.io/ceendi/deepfake-detector/frontend:latest --owner Ceendi
```

## Deployment verification

E2E starts the stack with `docker compose up --build --wait`, which builds every image from the
pull request's own code and waits for every healthcheck, then drives the real UI. Infrastructure
smoke restarts services and checks that state survives, that every file-service replica is
registered and scraped, that the Grafana dashboards and alert rules are valid and that traces reach
Tempo ([observability](observability.md)).

## Real models (E2E real models)

Pull request checks never load the detector models: they are not in the repository, and images
plus models need about 14 GB. `e2e-ml.yml` covers them on a schedule (1st and 15th of each month)
and on demand:

```bash
gh workflow run e2e-ml.yml                          # latest
gh workflow run e2e-ml.yml -f image_tag=sha-1234abc # a published commit
```

It pulls the published images of the chosen tag (`docker compose pull`, then `up --no-build`, so
it tests exactly what a plain `docker compose up` deploys), starts `core`, `auth` and `ml` with the
dev realm and one file-service replica, and waits until both detectors downloaded and loaded their
models. Media is generated in the job and never committed: three ffmpeg `flite` speech files (WAV,
MP3, FLAC), a test-pattern video without a face, and 30 s of a public-domain Wikimedia Commons
clip of one speaker, pinned by SHA-256 (a changed file fails the run). The batch CLI
(`tools/batch`) analyses the folder with `--labels-from-folders`;
`infra/ci/e2e_ml_outcomes.py` then requires a FAKE/REAL verdict with probabilities in [0, 1] for
every audio file and the speaker clip, and `FAILED NO_FACE_DETECTED` for the face-less video.
Verdicts themselves are not asserted: model quality is measured separately. A second run must
find nothing left to analyse (resume). The CSV, metrics and, on failure, `docker compose logs`
are uploaded as the `e2e-ml-report` artifact. The runner frees preinstalled toolchains first;
the job takes about 10 minutes.

## Security checks

Trivy (dependencies, images, configuration), `npm audit`, SonarCloud, Dependabot, SHA-pinned
actions and least-privilege job permissions. Details and what blocks a build:
[security](security.md).

## Running the same checks locally

```bash
cd orchestrator && ./mvnw verify                      # also gateway, file-service, eureka-server
cd audio-detector && uv sync --dev && uv run pytest tests && uvx ruff check .
cd frontend && npm ci && npm run lint && npm run test:ci && npm run build
cd frontend && npx playwright test                    # needs the core + auth stack running
```
