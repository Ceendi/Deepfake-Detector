# Local backend configuration

For first-time setup, model downloads and troubleshooting, use [SETUP.md](../SETUP.md).
This page covers configuration details of the Compose stack. Monitoring is described in
[observability.md](observability.md), backend failure behavior in [reliability.md](reliability.md).

## Java framework compatibility

The four Java services use Spring Boot 4.1.1 with Spring Cloud 2025.1.3. Cloud's
[compatibility table](https://spring.io/projects/spring-cloud/) supports Boot 4.1 starting
with 2025.1.2, so no release-train change is needed. The two MVC services use
[springdoc 3.1.1](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.1).
Java remains at 25. File-service's separate Tika migration is described below.

Review the [Boot upgrade notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes),
[configuration changelog](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1.0-Configuration-Changelog),
[Boot patch notes](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1) and
[Cloud release notes](https://github.com/spring-cloud/spring-cloud-release/releases/tag/v2025.1.3)
before moving these generations together. Project-specific checks:

| Area | Migration handling and regression coverage |
|---|---|
| Managed dependencies | Remove the Micrometer 1.16 override; Boot supplies 1.17.1 alongside Tracing 1.7.1. Keep the existing newer security pins for Jackson, Netty, Tomcat, HTTP Core and the Rabbit client. |
| Jackson / OpenAPI | Keep Jackson 3 and `JacksonJsonMessageConverter`. The existing orchestrator OpenAPI test checks the 429 body and `Retry-After` schema. springdoc 3.1.1 keeps MCP opt-in; no MCP starter is added. |
| AMQP retry | Adapt `AmqpRecoveryControl` and its test doubles to the new `org.springframework.amqp.core.MessageListenerContainer` returned by the endpoint registry in AMQP 4.1; the old Rabbit-specific interface is deprecated. Keep `max-retries: 3` (four total attempts) and the independent progress policy. Recovery lifecycle tests check shutdown callbacks and restarts; listener retry, database-outage and DLQ integration tests exercise the actual containers. |
| Gateway / Redis | Cloud Gateway remains 5.0.3. Warm-up still executes its bundled Lua script with rate, burst, time and requested-token arguments. Rate-limit integration tests cover real Redis, isolation and outage behavior; the readiness test checks warm-up ordering. |
| Eureka | Keep the existing server/client configuration and `lb://` routes. The server context test and infrastructure discovery test exercise startup, registration and routing. |
| Flyway / JPA | Boot manages Flyway 12.4 and Hibernate 7.4. Keep the Flyway starter and PostgreSQL module; use the default JPA bootstrap mode. Existing migrations remain immutable; migration and repository integration tests cover real PostgreSQL. |
| Process tests on Windows | Pass the outbox subprocess classpath through a Java argument file: the expanded dependency classpath exceeds the Windows command-line limit. The crash-after-confirm and restart assertions remain unchanged. |
| SSE stress tests on Windows | Send the existing minimum sample of more than 100 events before ending the stalled-reader setup loop, even if all three writers are already blocked. The whole class also reproduced this scheduling issue on the previous framework; preserve the blocked-writer, isolation and delivery assertions. |
| Actuator / observability | Keep health exposure, probes, tracing export and Prometheus configuration. Context tests scrape latency histograms; infrastructure and E2E tests check health and readiness. |
| Other upgrade notes | No layertools, Derby, jOOQ, gRPC, custom Tomcat base directory or deferred JPA bootstrap are configured. The application does not enable lazy JDBC connection fetching or new JWT authority expressions. |

`python infra/tests/test_java_platform.py` checks the compatible Boot/Cloud/springdoc
generations across all four independent POMs and rejects a Micrometer override below
the new BOM baseline. It runs in Infrastructure smoke for service changes. Full
`mvnw verify` in each Java service, followed by CI, E2E and Infrastructure smoke,
remains required for a framework migration. Dependabot's deliberate minor/major
upgrade exclusions remain in place.

## Upload MIME detection

File-service uses `tika-core` 4.1.0 only. Review the official
[migration guide](https://tika.apache.org/docs/4.1.x/migration-to-4x/migrating-to-4x.html),
[4.0 release notes](https://tika.apache.org/4.0.0/index.html) and
[4.1 release notes](https://tika.apache.org/4.1.0/index.html) before another upgrade.
The relevant migration changes are:

- Java 17 minimum is covered by the existing Java 25 runtime.
- `Detector.detect` now requires a `ParseContext`; create one per validation alongside
  empty `Metadata`, so neither the client MIME nor filename influences detection.
- `TikaInputStream` has different spooling/rewind rules. Keep the existing
  path-backed stream in try-with-resources; ffprobe opens the file independently.
- XML configuration, metadata-key migration, parser isolation, Markdown output and
  the 4.1 OCR/Pipes changes do not apply: no Tika parsers, server, configuration
  loader or extracted metadata are used. Do not add parser modules for detection.

`FfprobeMediaProbeIntegrationTest` generates media in temporary files and checks
exact MIME results for MP4/MOV/AVI/WAV/MP3/FLAC. The same corpus passes with
3.3.3 and 4.1.0, including WAV named `.mp4`, random bytes and real WebM. The
observed MIME results and retained aliases are in [the REST contract](contracts/rest-api.md).
Keep the whitelist and HTTP behavior unchanged when the corpus shows no difference.

Run `python infra/tests/test_java_platform.py` (the Tika-generation guard fails on
3.3.3) and `cd file-service; ./mvnw.cmd -B -ntp clean verify` on Windows with Docker,
ffmpeg and ffprobe available. The targeted comparison command is
`./mvnw.cmd -B -ntp "-Dtest=FileValidatorTest,FfprobeMediaProbeIntegrationTest" test`.
CI must also pass E2E and Infrastructure smoke. Dependabot exclusions stay unchanged.

## Java container tests

All four Java POMs pin `testcontainers.version` to 2.0.5, matching the Spring Boot
BOM. Gateway, orchestrator and file-service use the test-scoped
`testcontainers-junit-jupiter` module; the two database services also use
`testcontainers-postgresql`. Eureka has no direct Testcontainers dependency.

The [2.0 migration notes](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0)
rename modules with a `testcontainers-` prefix, relocate module-specific classes
and remove JUnit 4 support and default image constructors. PostgreSQL tests now
import the non-generic `org.testcontainers.postgresql.PostgreSQLContainer` and
still specify the same image explicitly. Tests already use JUnit Jupiter and
`@Container`; no `@ServiceConnection` is used, so `@DynamicPropertySource` wiring
stays in place. Generic containers for Redis and RabbitMQ keep their APIs.
The [2.0.5 patch notes](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.5)
require no further API adaptations for these services.

RabbitMQ tests must keep `Wait.forLogMessage(".*Server startup complete.*", 1)`:
Docker Desktop can accept connections on a mapped port before the broker is
ready for AMQP handshakes. Keep the existing images, assertions and retry policies.
Run Docker tests sequentially on a 16 GB development machine, with no other
agent running container tests:

```powershell
python infra/tests/test_java_platform.py
cd gateway; ./mvnw.cmd -B verify
cd ../eureka-server; ./mvnw.cmd -B verify
cd ../file-service; ./mvnw.cmd -B verify
cd ../orchestrator; ./mvnw.cmd -B verify
```

The platform regression tests reject mixed Testcontainers generations, legacy
module coordinates and dependencies outside test scope. They run in Infrastructure
smoke; Maven verification checks the resolved classpath and real container lifecycle.
CI, E2E and Infrastructure smoke must also pass before merging.

## Service discovery boundary

Eureka has no published host port. Its dashboard and registration API are available
at `http://eureka-server:8761` on the trusted Compose network. Services register and
fetch discovery data at `http://eureka-server:8761/eureka/`; the gateway routes through
`lb://SERVICE-NAME`. The gateway host port is bound to `127.0.0.1:8080`.

No registry credentials are needed for this closed network. Do not attach untrusted
containers to it or publish the registry in an override without adding authentication.

Port bindings change only when a container is recreated, not restarted:

```bash
docker compose --profile core up -d --force-recreate eureka-server gateway
```

Inspect the registry without opening a host port:

```bash
docker compose exec gateway wget -qO- http://eureka-server:8761/eureka/apps
```

`python3 infra/tests/test_discovery.py` (Linux/macOS, Docker Compose) builds the services in
a disposable project and checks that host-side registration is refused, services register,
and authenticated requests reach file-service and orchestrator through the gateway, also
after restarts. It is a single-host test; it does not prove isolation from other LAN hosts.

## Authentication URLs

The Keycloak realm `deepfake` is configured from
[realm-export.json](../infra/keycloak/realm-export.json). See the
[Keycloak guide](../infra/keycloak/README.md) for account policy and realm management.

`KC_HOSTNAME` sets the public base URL to `http://localhost:8180` by default.
Tokens carry `http://localhost:8180/realms/deepfake` in their `iss` claim. Java services
validate it against `JWT_ISSUER_URI` and fetch signing keys from
`JWK_SET_URI=http://keycloak:8080/realms/deepfake/protocol/openid-connect/certs` over the
Compose network (inside a container, `localhost` is the container itself). The frontend
uses the public localhost URL for login redirects.

## Object storage credentials

Buckets, identities and access scopes are defined in the
[object-storage contract](contracts/object-storage.md).

`seaweedfs-init` renders `infra/seaweedfs/s3.json.tmpl` into the shared `seaweedconfig`
volume before SeaweedFS starts, using `render_config.py` in a digest-pinned Python image.
It requires a non-empty `S3_<ID>_KEY` and `S3_<ID>_SECRET` for each of `ADMIN`,
`FILE_SERVICE`, `ORCHESTRATOR` and `DETECTOR`. Values are inserted as whole JSON strings, so
special characters are kept verbatim; control characters are rejected and access keys must be
distinct. SeaweedFS strips spaces from access keys when verifying signatures, so do not use
spaces in keys.

The renderer validates everything before writing and replaces `s3.json` atomically; on failure
it exits non-zero, keeps the previous file, and SeaweedFS does not start. Rendering does not
reconfigure a running SeaweedFS: after changing credentials, recreate `seaweedfs-init`,
`seaweedfs`, `seaweedfs-bucket-init` and the affected clients. Never delete the storage volumes
to apply a credential change. `python3 infra/tests/test_s3_configuration.py` checks the
renderer and real signed access per identity in an isolated project.

The same init writes `security.toml` with fresh JWT signing keys on every run, and SeaweedFS
exposes only the S3 API to other containers; master, volume and filer listen on loopback. See
[broker users and internal storage APIs](reliability.md#broker-users-and-internal-storage-apis).

## Messaging

RabbitMQ exchanges, queues, bindings and message formats are documented in the
[AMQP contract](contracts/amqp-messages.md). Application code declares the topology at
startup; the broker needs no definitions file. `rabbitmq-init` creates the least-privilege
`orchestrator` and `detector` users (`RABBITMQ_ORCHESTRATOR_PASSWORD`,
`RABBITMQ_DETECTOR_PASSWORD`); `RABBITMQ_USER` is the administrator for manual operations only.
See [broker users and internal storage APIs](reliability.md#broker-users-and-internal-storage-apis).

## Locally built infrastructure images

Some infrastructure images are rebuilt from pinned upstream bases to remove vulnerable
components; rebuild them after changing their Dockerfiles:

```bash
docker compose --profile core --profile auth --profile monitoring build \
  postgres keycloak-db keycloak-config-cli alloy
```

| Image | Source | Change |
|---|---|---|
| `deepfake-postgres` (`postgres`, `keycloak-db`) | `infra/postgres` | Replaces `gosu` with `su-exec`; runs as `postgres` |
| `deepfake-keycloak-config-cli` | `infra/keycloak-config-cli` | Upstream v6.5.1 built with patched dependencies; UID 65534 |
| `deepfake-alloy` | `infra/alloy` | Patched OpenSSL; image default UID 65534 |

The development Compose service still runs Alloy as root to read the Docker socket; that is a
dev-only trade-off. The **Security scan** workflow scans every Compose image with Trivy: CRITICAL
findings fail it, HIGH findings (including ones without a vendor fix) are reported, so a green
scan does not mean zero CVEs.

Infrastructure regression tests in `infra/tests/` run in disposable Compose projects and are
part of the **Infrastructure smoke** workflow: `test_discovery.py`, `test_keycloak.py`
(realm import and password-reset flow), `test_tempo.py`, `test_s3_configuration.py`,
`test_analysis_file_ownership.py` and `test_security_report.py`.
