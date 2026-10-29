# Security

How the system protects user media and accounts, and how security is checked in CI. Mechanism
details live in [reliability](reliability.md), [Keycloak configuration](../infra/keycloak/README.md)
and the [contracts](contracts/); this page is the overview.

Users upload private recordings of faces and voices, so the design assumes two kinds of attacker:
an outside user of the public API and a compromised internal component. Each service checks the
caller itself instead of trusting the network.

## Authentication and authorization

- **Login:** OIDC authorization code flow with PKCE (`S256`) against Keycloak. The realm is
  versioned as code ([ADR 0001](adr/0001-keycloak-realm-as-code.md)); roles are not edited in the
  admin UI.
- **Tokens:** access tokens live 15 min, SSO sessions 30 min idle / 10 h max. Brute-force protection
  locks an account after 10 failed logins (exponential, up to 15 min). The SPA sends the token in the
  `Authorization` header, also for SSE, never in a URL.
- **Every service validates the JWT itself:** the gateway, orchestrator and file-service check the
  signature against the Keycloak JWKS, the issuer and the expiry. The orchestrator and file-service
  also check the token's client (`azp`) against an allow-list (`JWT_ALLOWED_CLIENTS`, default
  `deepfake-web,deepfake-cli,deepfake-loadtest`), so a token minted for another Keycloak client is
  rejected even when its signature is valid. `deepfake-cli` is the batch CLI's public client; it
  allows only the device authorization grant, so it signs in a real user.
- **Ownership:** every endpoint that returns a user resource checks `resource.user_id == jwt.sub`
  and answers **404**, not 403, so other users' IDs cannot be probed (IDOR).
- **Internal endpoints:** gateway Actuator listens on internal port 9090 and checks the real local
  socket port; forwarded headers cannot make a request internal. Anonymous health responses carry
  no component details.

## Uploads

A file is accepted only if all three checks pass, never on the extension or the declared type:

1. magic bytes (Apache Tika),
2. `ffprobe` parses it as a real audio/video container,
3. the detected MIME type is on the whitelist (MP4, MOV, AVI, WAV, MP3, FLAC).

Files over 500 MiB are rejected with `413` before they are stored
([REST contract](contracts/rest-api.md)). Objects get server-generated keys; the original file name
is metadata only.

## Abuse limits

| Limit | Where | Default |
|---|---|---|
| Upload rate per user | gateway (Redis token bucket) | 10/min, burst 10 |
| Other API calls per user | gateway | 2/s, burst 100 |
| Concurrent uploads | file-service | 2 per user, 4 total |
| Storage quota | file-service | 5 GiB per user |
| Active analyses | orchestrator | 3 per user, 20 total |

Over-limit requests get `429` (with `Retry-After` where the service knows when to retry). The
gateway upload limiter fails **closed** (`503`) when Redis is down, so an outage cannot disable
upload throttling; other Redis uses fail open ([reliability](reliability.md)).

## Least privilege between components

- **PostgreSQL:** the cluster administrator is used only for provisioning and manual operations.
  The orchestrator uses `ORCHESTRATOR_DB_USER/PASSWORD`, owns its application database and tables,
  and has no superuser, database/role creation, replication or RLS-bypass privileges. file-service
  has its own role and database. Existing data needs the [role migration](../SETUP.md#existing-postgresql-volume-role-migration).
- **Discovery:** Eureka requires HTTP Basic for its dashboard and `/eureka/**` API. Only health
  is anonymous. `EUREKA_USER/PASSWORD` configure the registry and all three Java clients; CSRF
  stays enabled outside `/eureka/**`, whose machine clients authenticate every request with Basic.
  Use URL-safe credentials because client URLs carry them. Do not log those URLs. The shared
  credential limits registration to its holders; a compromised Java client can still register
  arbitrary instances. HTTP is a local/demo trade-off, so use TLS before deploying across hosts.
- **Object storage:** separate SeaweedFS S3 identities. file-service reads/writes only
  `deepfake-uploads`; the orchestrator only `analysis-artifacts`; detectors read uploads and write
  artifacts. SeaweedFS internal APIs stay on loopback and are signed with per-deployment JWT keys.
- **RabbitMQ:** services never use the admin account or `guest`. The `orchestrator` and `detector`
  users get only the queues and publish routing keys they need, enforced with topic permissions
  ([broker users](reliability.md#broker-users-and-internal-storage-apis)).
- **Network:** every published port binds to `127.0.0.1`; databases, broker, Redis and storage are
  not reachable from other machines.
- **Containers:** Eureka, gateway, file-service, orchestrator and monitoring drop all Linux capabilities, forbid
  privilege escalation and limit process count; several run with a read-only root filesystem. The
  frontend image runs unprivileged nginx.
- **Secrets:** `.env` (or Infisical) only, never committed; `.env.example` holds placeholders.

Alloy runs as root with the Docker socket mounted using `:ro`. That flag protects the socket
inode, **not Docker API operations**: a compromised Alloy can create privileged containers and
access every host/VM volume. This is a development-only, host-equivalent trust decision. Project
label filtering limits collected logs, not API permissions. A production deployment must remove
the direct socket mount or introduce an authenticated proxy that permits only the required
container discovery/log/event reads and denies writes.

## Security in the delivery pipeline

| Check | Workflow | Blocks the build when |
|---|---|---|
| SonarCloud static analysis (Java, TypeScript, Python, Dockerfiles, workflows) | `ci.yml` | Quality Gate fails on new code |
| Trivy, application dependencies (Maven, npm, uv) | `ci.yml` | a HIGH/CRITICAL finding has a fix |
| `npm audit --audit-level=high` | `ci.yml` | a high advisory exists |
| Trivy, detector images | `ci.yml` | a HIGH/CRITICAL finding has a fix |
| Trivy, every Compose image + SARIF to Code Scanning | `security-scan.yml` (also weekly) | a CRITICAL finding has no active, owner-approved acceptance |
| Trivy, every published image (incl. `frontend` and `keycloak`) before push | `build-push.yml` | any CRITICAL finding (the image is not published) |
| Trivy, Compose/Dockerfile configuration | `security-scan.yml` | report only |
| SBOM (CycloneDX, Syft) | `security-scan.yml` | artifact only |

Supporting practices: Dependabot for every ecosystem (grouped, weekly) plus Dependabot security
updates for vulnerable transitive dependencies; all third-party actions
pinned to full commit SHAs; `harden-runner` on the security jobs; least-privilege `permissions:`
per job; npm installs in CI and images run without lifecycle scripts; images are published only
after a successful CI push run on main. Exact scanned archives are pushed with immutable tags;
all images must pass before publication, all pushes before attestation, and all attestations
before latest promotion. Rebuilds use a new run-suffixed tag. Older commits cannot move latest.
PR image jobs have read-only permissions; writes, OIDC and attestation permissions exist only
in non-PR jobs. See [required checks and publication](ci-cd.md).
The owner must require `ci-ok`, `e2e-ok`, `infra-ok`, `security-ok` and `images-ok` in branch
protection. Each fails on failed/cancelled prerequisites; intentional skips pass. Fork PRs lack
Sonar credentials: the summary records this omission, and maintainers must obtain a green Sonar
run on an in-repository branch before merging.
Code Scanning closes alerts of image categories that are no longer scanned, so the
list reflects the current deployment.

Reviewed SonarCloud exceptions (operator-supplied CLI paths, the contract upload limit, an
immutable migration) are recorded with their reason in `sonar-project.properties`.

### Temporary Grafana risk acceptance

The repository owner approved accepting CVE-2026-56857 in Grafana 13.2.5 through
**12 November 2026, Europe/Warsaw**. The machine-readable entry in
[`infra/security/accepted-risks.json`](../infra/security/accepted-risks.json) expires at
`2026-11-13T00:00:00+01:00` (exclusive). It matches the exact image digest, Go package version
and six bundled-plugin binary paths, so a different image, target or new CRITICAL still blocks.

This is a temporary acceptance for the local/demo deployment while upstream plugin packages
are fixed. Grafana HTTP is bound to loopback and anonymous access is disabled; these controls
limit exposure but do not remove the vulnerability. The Compose scan still reports every
finding in raw JSON, SARIF, Code Scanning and the job summary. An active acceptance emits a
GitHub warning and allows the job to pass. After expiry the finding blocks automatically.
Publishing gates do not load this acceptance file and remain strict for every CRITICAL.
Remove the entry once an updated official image passes the scan; extending it needs explicit
owner approval. No application setting or plugin is changed by this acceptance.

## Known gaps

Accepted for the local and demo deployment, required before a public production deployment
(tracked in [issue #236](https://github.com/Ceendi/Deepfake-Detector/issues/236)):

- HTTPS everywhere and Keycloak in production mode with a real hostname.
- Separate PostgreSQL roles (admin, migration owner, runtime) and Redis ACL users per service.
- Password reset stays disabled until SMTP is configured.
