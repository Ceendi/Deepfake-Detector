# CI/CD pipeline

GitHub Actions builds, tests, analyses, scans and publishes the system. Workflow sources are
in `.github/workflows/`; security policies are described in [security](security.md).

## Flow and scope

CI, E2E, Infrastructure smoke, Security scan and build-push always emit a result for every PR.
Path selection happens inside jobs, using both names of renamed files. Empty or unreadable
selections run checks. Pushes to main run all CI, E2E, infrastructure and security checks.
build-push publishes after successful main CI, on schedule and on demand. E2E real models runs
only on schedule and on demand. Superseded PR runs are cancelled. Active publications finish,
but GitHub may replace an older pending publication; concurrency is not a FIFO queue.

| PR changes | CI test selection |
|---|---|
| `frontend/` | frontend; Java services compile classes for Sonar |
| one Java service | that service's Maven verify; the others compile |
| detectors, detector-runtime, worker fixtures | detector jobs and orchestrator worker tests |
| shared wire fixtures | orchestrator, detectors and frontend |
| docs, root Markdown, tools | guards, Python lint/tools tests, Java compilation, dependency scan, Sonar |
| other/shared paths | everything |

Selection is defined in `infra/ci/ci_scope.py`, `workflow_scope.py` and `workflow_paths.json`.
Static guards, Python lint/tools tests and dependency scans run on every PR. The dependency scan
also runs after Java failures. E2E runs if its selection job fails. Sonar runs for in-repository
PRs even after test failures. Fork PRs lack SONAR_TOKEN: `ci-ok` explicitly records the omitted
analysis. Maintainers must copy a fork PR to an in-repository branch and obtain a green Sonar run
before merging; secrets are never exposed to fork code.

## Required branch protection checks

After merging, the owner must replace the old required-check list with these exact GitHub Actions
contexts and keep "Require branches to be up to date":

- `ci-ok`
- `e2e-ok`
- `infra-ok`
- `security-ok`
- `images-ok`

Each aggregator uses `if: always()`, waits for **every** job in its workflow and fails on any
failed or cancelled prerequisite. Intentional path/event skips pass. These replace individual
required entries `Test report`, `SonarCloud Quality Gate`, `java (...)`, `frontend`, `playwright`.
Workflow files do not change repository settings: the owner must apply this list for enforcement.

| Context | Covered work |
|---|---|
| `ci-ok` | all Java/Python/detector/tools tests and lint, dependencies, detector image security, static guards, commitlint, JUnit report, Sonar |
| `e2e-ok` | selection and Playwright on the real core + auth stack |
| `infra-ok` | selection, monitoring, restart/persistence, discovery, Keycloak, S3/RabbitMQ permissions, analysis input ownership, manual fault acceptance when selected |
| `security-ok` | selection, every Compose image scan, configuration scan, SBOM and main-only orphan alert cleanup |
| `images-ok` | selection, image builds/scans/model smoke and, outside PRs, publish, attest and promotion |

## Tests and reports

Java uses JUnit/Testcontainers and JaCoCo; Python uses pytest/coverage; frontend uses vitest and
Playwright. Reports are uploaded as `test-results-*` and `e2e-report` artifacts for 14 days.
`Test report` summarizes JUnit XML with `fail_on_failure: true`; originating test jobs fail
independently. E2E's JUnit report also fails on failed tests.

Container-free `infra/tests` guards run unconditionally through `python3 infra/tests/run_static.py`.
Compose guards use only `docker compose config`, without starting containers. Stack tests remain
path selected in Infrastructure smoke. The PR-title job uses the repository's commitlint config
in strict mode and reruns when the title is edited.

## Static analysis and Quality Gate

SonarCloud analyses Java, TypeScript, Python, Dockerfiles and workflows as one project. It imports
JaCoCo, lcov and coverage.py reports and waits for the Quality Gate (`sonar.qualitygate.wait=true`).
The configured "Sonar way" gate checks new code: rating A for bugs/vulnerabilities, reviewed
security hotspots, at least 80% coverage and at most 3% duplication. New code is the diff against
main for PRs and the last 30 days on main. Reviewed exceptions and their reasons are recorded in
`sonar-project.properties`. ESLint, ruff and TypeScript checks run in CI as well.

CI-only Python dependencies, including transitive packages, are exact pins under `infra/ci/`
and `infra/ci/test-requirements/`, managed by pip Dependabot. `requirements-light.txt` delegates
to those pins and installs the local runtime from source. Regenerate with `uvx uv@0.12.23 pip compile`
using the command recorded in each file. Detector production dependencies still use uv.lock.

## Images and versions

The eleven images are `gateway`, `eureka-server`, `orchestrator`, `file-service`, `video-detector`,
`audio-detector`, `keycloak`, `frontend`, `postgres`, `keycloak-config-cli`, `alloy`.
`infra/tests/test_compose_images.py` checks the inventory against Compose.

Schedule/manual publication resolves the latest successful **CI push run on main** via the API,
rather than trusting current HEAD. No green run means publication fails. workflow_run validates
a successful push from this repository and checks out that run's SHA.

Every image is built once, loaded locally, scanned with Trivy and, for detectors, smoke-tested
with pinned model weights. Every pinned HF file receives a remote metadata request even on a
verified model-cache hit. The exact scanned image is saved as a Docker archive (one-day artifact).
Only after **all eleven** build/scan/smoke jobs pass do separate publish jobs load and push the
archives. There is no second build. Registry writes exist only in non-PR publish/attest/promote
jobs; OIDC and attestations write permissions are confined to non-PR attestation jobs.

The first ordinary publication uses `sha-<7-character commit>`. Once any image has that tag,
subsequent runs use `sha-<commit>-r<run_number>-a<run_attempt>` for the entire set. Scheduled and
manual no-cache rebuilds always use the suffixed tag. A partial job rerun refuses to replace an
existing immutable tag with different content. The plain SHA tag is never refreshed by a rebuild.
`DEEPFAKE_IMAGE_TAG=sha-1234abc-r42-a1` selects one rebuilt set; `latest` remains the default.
Weekly/no-cache rebuilds refresh the base and every layer to pick up OS fixes.

After **all pushes** succeed, every digest is attested. After **all attestations** succeed, a
single `promote` job uses `docker buildx imagetools create --prefer-index=false` to move `latest`,
preserving the scanned manifest. Before each retag it requires source SHA = current main tip.
An older green CI rerun, or a schedule selecting earlier green CI while HEAD is red, can publish
immutable tags but cannot roll latest backwards. Matrix failures never promote latest. A failed
publication can leave a partial immutable tag set: do not deploy it. GHCR has no transaction
spanning eleven tags, so a registry outage during promotion can still interrupt retagging.
Deploy a complete immutable set (or digests) for reproducibility.

Images are published under `ghcr.io/ceendi/deepfake-detector/<image>:<tag>` with an OCI revision
label. Verify signed provenance with:

```bash
gh attestation verify oci://ghcr.io/ceendi/deepfake-detector/frontend:latest --owner Ceendi
```

The frontend reads Keycloak settings from `/config.js` at container start.

## Deployment verification

E2E starts the stack with `docker compose up --build --wait`, which builds every image from the
pull request's own code and waits for every healthcheck, then drives the real UI. Infrastructure
smoke restarts services and checks that state survives, that every file-service replica is
registered and scraped, that the Grafana dashboards and alert rules are valid and that traces reach
Tempo ([observability](observability.md)).

## Real models (E2E real models)

The full application with real models needs about 14 GB. Image-definition PRs run synthetic model
smoke tests; the complete real-media flow is covered separately. `e2e-ml.yml` covers them on a schedule (1st and 15th of each month)
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
