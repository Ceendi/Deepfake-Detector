# Local backend configuration

Configuration details of the Compose stack. First-time setup, models and troubleshooting are in
[SETUP.md](../SETUP.md), monitoring in [observability.md](observability.md) and failure behaviour
in [reliability.md](reliability.md).

## Java services and tests

Versions live in each service's `pom.xml`; the non-obvious stack rules (Jackson 3, Spring Cloud
train, `max-retries`) are listed in `AGENTS.md`. `python infra/tests/test_java_platform.py` keeps
the four independent POMs on consistent Spring Boot, Spring Cloud, springdoc and Testcontainers
generations, rejects a Micrometer override below the Boot BOM and checks that file-service uses
only `tika-core` (no Tika parsers) for upload MIME detection. Run it, then `mvnw verify` in every
service, before a framework upgrade.

Integration tests use Testcontainers with real PostgreSQL, Redis and RabbitMQ. RabbitMQ containers
must wait for `Wait.forLogMessage(".*Server startup complete.*", 1)`: Docker Desktop can accept
connections on a mapped port before the broker is ready for AMQP. On a 16 GB machine run the
Docker test suites one service at a time:

```powershell
python infra/tests/test_java_platform.py
cd gateway; ./mvnw.cmd -B verify
cd ../eureka-server; ./mvnw.cmd -B verify
cd ../file-service; ./mvnw.cmd -B verify     # also needs ffmpeg and ffprobe on PATH
cd ../orchestrator; ./mvnw.cmd -B verify
```

## Service discovery boundary

Eureka has no published host port. Its dashboard and registration API are available at
`http://eureka-server:8761` on the Compose network; services register at
`http://eureka-server:8761/eureka/` with HTTP Basic and the gateway routes through `lb://SERVICE-NAME`.
Set `EUREKA_USER` and `EUREKA_PASSWORD` in `.env` (URL-safe characters). Compose passes an authenticated
`EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE` to gateway, orchestrator and file-service. IDE runs need
these credentials too, or an explicit authenticated registry URL. Only `/actuator/health` is public.
The gateway host port is bound to `127.0.0.1:8080`.

Inspect the registry without opening a host port:

```bash
docker compose exec gateway sh -c 'wget -qO- "${EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE}apps"'
```

Port bindings change only when a container is recreated, not restarted
(`docker compose up -d --force-recreate eureka-server gateway`). `python3 infra/tests/test_discovery.py`
(Linux/macOS) checks that host-side registration is refused, internal anonymous/incorrect-password
registration gets 401, and authenticated requests reach the services
through the gateway, also after restarts.

## Authentication URLs

The realm `deepfake` is configured from [realm-export.json](../infra/keycloak/realm-export.json);
see the [Keycloak guide](../infra/keycloak/README.md). `KC_HOSTNAME` makes every token carry
`iss = http://localhost:8180/realms/deepfake`. Java services validate it against `JWT_ISSUER_URI`
and fetch signing keys over the Compose network from
`JWK_SET_URI=http://keycloak:8080/realms/deepfake/protocol/openid-connect/certs` (inside a
container `localhost` is the container itself). The frontend uses the public URL for login
redirects.

## Object storage credentials

Buckets, identities and access scopes are defined in the
[object-storage contract](contracts/object-storage.md).

`seaweedfs-init` renders `infra/seaweedfs/s3.json.tmpl` into the `seaweedconfig` volume before
SeaweedFS starts. It requires a non-empty `S3_<ID>_KEY` and `S3_<ID>_SECRET` for `ADMIN`,
`FILE_SERVICE`, `ORCHESTRATOR` and `DETECTOR`; access keys must be distinct and contain no spaces or
control characters. The renderer validates everything first and replaces `s3.json` atomically; on
failure it keeps the previous file and SeaweedFS does not start. It also writes `security.toml` with
fresh JWT signing keys on every run ([internal storage APIs](reliability.md#broker-users-and-internal-storage-apis)).

Rendering does not reconfigure a running SeaweedFS: after changing credentials recreate
`seaweedfs-init`, `seaweedfs`, `seaweedfs-bucket-init` and the affected clients. Never delete the
storage volumes to apply a credential change. `python3 infra/tests/test_s3_configuration.py`
checks the renderer and real signed access per identity.

## Messaging

Exchanges, queues, bindings and message formats are in the [AMQP contract](contracts/amqp-messages.md).
Application code declares the topology at startup; the broker needs no definitions file.
`rabbitmq-init` creates the least-privilege `orchestrator` and `detector` users
(`RABBITMQ_ORCHESTRATOR_PASSWORD`, `RABBITMQ_DETECTOR_PASSWORD`); `RABBITMQ_USER` is the
administrator for manual operations only. See
[broker users](reliability.md#broker-users-and-internal-storage-apis).

## Patched infrastructure images

Some infrastructure images are rebuilt from pinned upstream bases to remove vulnerable components.
They are published to GHCR with the application images; rebuild them locally after changing their
Dockerfiles:

```bash
docker compose --profile core --profile auth --profile monitoring build \
  postgres keycloak-db keycloak-config-cli alloy
```

| Image (`ghcr.io/ceendi/deepfake-detector/…`) | Source | Change |
|---|---|---|
| `postgres` (services `postgres`, `keycloak-db`) | `infra/postgres` | Replaces `gosu` with `su-exec`; runs as `postgres` |
| `keycloak-config-cli` | `infra/keycloak-config-cli` | Upstream v6.5.1 built with patched dependencies; UID 65534 |
| `alloy` | `infra/alloy` | Patched OpenSSL; image default UID 65534 |

The dev Compose service still runs Alloy as root with full Docker API access: the socket's `:ro`
mount does not prevent API writes or host takeover. What the security
scans block is described in [security](security.md#security-in-the-delivery-pipeline).

## Infrastructure tests

`infra/tests/` holds the infrastructure regression tests. Those that start Docker run in disposable
Compose projects in the **Infrastructure smoke** workflow (discovery, Keycloak, Tempo, S3, RabbitMQ
users, restarts and scaling); the rest only read configuration. `test_compose_images.py` checks
that every image Compose builds is also published by `build-push.yml` from the same build inputs.
