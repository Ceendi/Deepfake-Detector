# Local backend configuration and operations

For first-time setup, model downloads and troubleshooting, use [SETUP.md](../SETUP.md).
This document covers configuration details and changes to an existing development stack.

## Service discovery boundary

Eureka has no published host port. Its dashboard and registration API are available
at `http://eureka-server:8761` on the trusted Compose network. Services register and
fetch discovery data at `http://eureka-server:8761/eureka/`; the gateway routes through
`lb://SERVICE-NAME`. The gateway host port is bound to `127.0.0.1:8080`.

No registry credentials are needed for this closed local network. Do not attach
untrusted containers to that network or publish the registry in an override without
adding authentication. The instance address preferences are unchanged.

### Updating an existing stack

Apply the removed Eureka publication and the gateway loopback binding by recreating
the affected containers:

```bash
docker compose --profile core up -d --force-recreate eureka-server gateway
```

This preserves named volumes. A plain container restart does not update port bindings.
Do not remove persistent volumes to apply this network configuration change.

Dependency upgrades have separate rollout instructions in [compose-upgrade.md](compose-upgrade.md).

### Verification

Inspect the registry without opening a host port:

```bash
docker compose exec gateway wget -qO- http://eureka-server:8761/eureka/apps
```

Run the isolated regression with Python 3.9+ and Docker Compose on Linux or macOS:

```bash
python3 infra/tests/test_discovery.py
```

The regression builds the real Java services and uses fresh project-scoped storage,
networks and test credentials. Only gateway, a disposable Keycloak realm and a TCP
echo control publish test ports. These ports are checked unused and selected outside
the host's ephemeral source-port range to avoid collisions with outbound connections.
Gateway and Keycloak retain their loopback bindings; the control uses a wildcard
binding to verify that the external probe can reach the host. Cleanup removes only
the explicitly named test project's resources.

The test checks that host-side registration fails at the socket boundary, legal
services register UP, and authenticated requests reach file-service and orchestrator
through the gateway's existing discovery routes. It repeats these checks after
restarting Eureka, gateway, file-service and orchestrator with preserved storage.

The external probe runs on a separate Docker bridge network and targets the host's
real non-loopback interface. The positive control must succeed before closed-port
checks can pass. Docker Desktop/OrbStack's `host.docker.internal` alias can forward
loopback ports to local containers and is not used as evidence of LAN isolation.

This is a single-host test. When a second LAN host is available, also verify that
host port 8761 and the published gateway port are unreachable from it. Full ML
inference and the cross-PR end-to-end acceptance suite are not covered by this test.

## Authentication URLs

The Keycloak realm `deepfake` is configured from
[realm-export.json](../infra/keycloak/realm-export.json). See the
[Keycloak guide](../infra/keycloak/README.md) for account policy and realm management.

`KC_HOSTNAME` sets the public base URL to `http://localhost:8180` by default.
Tokens for the `deepfake` realm carry `http://localhost:8180/realms/deepfake` in
their `iss` claim. The backend validates it against
`JWT_ISSUER_URI=http://localhost:8180/realms/deepfake`, while fetching signing keys
from `JWK_SET_URI=http://keycloak:8080/realms/deepfake/protocol/openid-connect/certs`
over the Compose network. Inside a container, `localhost` refers to that container.
The frontend uses the public localhost URL for login redirects.

## Storage and messaging

Bucket identities, access scopes and bootstrap behavior are documented in the
[object-storage contract](contracts/object-storage.md). RabbitMQ exchanges, queues,
bindings and message formats are documented in the [AMQP contract](contracts/amqp-messages.md).
Application code declares the broker topology at startup.
