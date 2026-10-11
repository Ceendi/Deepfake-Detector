# Local backend configuration

For first-time setup, model downloads and troubleshooting, use [SETUP.md](../SETUP.md).
This page covers configuration details of the Compose stack. Monitoring is described in
[observability.md](observability.md), backend failure behavior in [reliability.md](reliability.md).

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

## Messaging

RabbitMQ exchanges, queues, bindings and message formats are documented in the
[AMQP contract](contracts/amqp-messages.md). Application code declares the topology at
startup; the broker needs no definitions file.

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
