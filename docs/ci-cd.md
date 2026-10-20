# CI/CD pipeline

GitHub Actions builds, tests, analyses, scans and publishes the system. This page explains the
flow and where each result can be found. Workflow files are in `.github/workflows/`.

## Flow

```text
pull request ─► CI ──────────────► tests, coverage, SonarCloud Quality Gate, dependency scans
             ├► E2E ─────────────► full core + auth stack in Compose, Playwright in Chromium
             ├► Infrastructure smoke ► restarts, discovery, Keycloak, S3/RabbitMQ least privilege
             ├► Security scan ───► Trivy on every Compose image and on Dockerfile/Compose config
             └► build-push ──────► images are built, not pushed (only when an image definition changes)

push to main ─► the same workflows
              └► CI passes ─► build-push ─► GHCR: sha-<commit> + latest
```

Every workflow runs on pull requests and on pushes to `main`. Pull request runs that are
superseded by a new push are cancelled; runs on `main` always finish. Path filters skip
workflows that a change cannot affect (for example E2E for a documentation-only change).

## Workflows

| Workflow | Trigger | Jobs | Fails when |
|---|---|---|---|
| `ci.yml` (CI) | PR, push | `java (*)` (Maven verify of 4 services), `python (*)` (ruff), `Lightweight detector contract (*)`, `ML dependency compatibility (*)`, `Worker process redelivery`, `frontend` (lint, vitest, build, npm audit), `Application dependency vulnerabilities`, `Detector image security and compatibility (*)`, `Test report`, `SonarCloud Quality Gate` | a test or lint fails, the Quality Gate fails, a fixable HIGH/CRITICAL dependency or image vulnerability exists |
| `e2e.yml` (E2E) | PR (app paths), push | `playwright`: `docker compose up --wait` of the core and auth profiles, then Playwright through Keycloak login, upload and analysis | a service is unhealthy or a browser test fails |
| `infra-smoke.yml` | PR (infra paths), push | restart and persistence, Prometheus scraping of every replica, Tempo ingest, Keycloak import, discovery isolation, S3 and RabbitMQ permissions | a running stack does not behave as configured |
| `security-scan.yml` | PR (image paths), push, weekly | Trivy per Compose image (SARIF to Code Scanning), Trivy config scan, SBOM, closing alerts of removed images | an image has a CRITICAL vulnerability |
| `build-push.yml` | after CI on `main`, PR (image paths), manual | `image (*)` for 8 images | an image does not build |

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
`audio-detector`, `keycloak` (with the login theme) and `frontend` (static build served by
unprivileged nginx). Each image carries the OCI `revision` label of its commit. A failed CI run
publishes nothing. Pull requests that change an image definition build all images without pushing.

## Deployment verification

The E2E workflow starts the stack from the commit with `docker compose up --build --wait`, which
waits for every healthcheck, and then drives the real UI. Infrastructure smoke restarts services
and checks that state survives, that every file-service replica is registered and scraped, and that
traces reach Tempo.

## Observability in the pipeline

Infrastructure smoke validates the Grafana dashboards and Prometheus alert rules, scrapes
`/actuator/prometheus` of every replica through a real Prometheus, and writes and reads back a
trace in Tempo. See [observability](observability.md).

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
