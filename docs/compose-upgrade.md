# Compose dependency upgrade: runtime validation and rollout

The image updates in PR #73 require a Tempo configuration migration. Tempo 3.1.0
rejects the old `ingester` and `compactor` sections and exits before serving traces.
The rest of this Compose stack keeps the same ports, credentials and API contracts.

## Tempo configuration

This is a monolithic deployment; Kafka is not required. `live_store` replaces the
ingester, keeps the five-minute block duration and writes its WAL under
`/var/tempo/live-store/traces` inside the existing `tempo-data` volume. Completed
blocks stay at `/var/tempo/blocks`. Retention moves to
`overrides.defaults.compaction.block_retention` and remains 72 hours.
The OTLP gRPC/HTTP receivers remain on 4317/4318, including the existing
`http://tempo:4318/v1/traces` exporter endpoint.

Follow the [official Tempo 3 migration guide](https://grafana.com/docs/tempo/latest/set-up-for-tracing/setup-tempo/migrate-to-3/).
Do not run both versions against the same writable storage simultaneously.

For an existing Tempo 2.10.6 installation, drain its traces before replacing it:

1. Stop trace producers, keeping the old monitoring stack running. Back up the
   old configuration and persistent data before changing image versions.
2. While still using Tempo 2.10.6 and its old YAML, add
   `ingester.flush_all_on_shutdown: true` under the existing `ingester` section.
   Recreate only the old Tempo container with this configuration. This also
   replays its existing `/var/tempo/wal`.
3. Wait for readiness, then stop it with `docker compose --profile monitoring
   stop --timeout 90 tempo`. Check the logs for successful block completion and
   flushes. Increase the timeout for a larger backlog; do not proceed if the
   container was forcibly killed or flushing failed. A graceful stop with this
   setting drains traces into `/var/tempo/blocks`.
4. Apply the new image and YAML together, preserving `tempo-data`. Start the
   updated monitoring profile. Verify `/ready`, query a known historical trace
   through `/api/traces/<trace-id>`, and query a new trace after resuming producers.

The new live-store WAL does not replace draining the old ingester WAL. Existing
completed vParquet4 blocks are covered by the integration test. There is no
supported in-place downgrade to Tempo 2; rollback requires the pre-upgrade
configuration and backup, not just reverting the image tag. Never use `down -v`
for an existing development stack whose data must survive.

## Other images

PostgreSQL stays on major 18. Both databases retain their existing PGDATA and
volumes. Keycloak stays on major 26; the pinned config CLI was verified against
26.8.0 by importing this repository's realm and issuing a load-test client token
with the expected issuer and USER role. The tracked custom login-theme JAR also
renders successfully. Its rebuild requires Node/npm, Java and Maven; a rebuild is
not required by this image update.

Grafana 13.1.7 provisions the existing fixed datasource UIDs and business dashboard.
The Prometheus, Loki and Tempo datasource health APIs succeed. Alloy 1.20.1 reads
Docker stdout and sends the trace/correlation metadata to Loki 3.7.8. SeaweedFS
4.48 and AWS CLI 2.37.9 provision both buckets and support the current S3 identity
scopes. Orchestrator's write action is intentional: artifact deletion uses it.

## Verification

Run `python3 infra/tests/test_tempo.py` with Docker Compose available. The test
uses the actual Compose image/config, random localhost ports, a unique network
and disposable volumes. It checks readiness, effective retention/storage settings,
OTLP HTTP ingestion, trace lookup after restart, and migration of a flushed Tempo
2.10.6 trace to 3.1.0. The Infrastructure smoke workflow runs it on relevant PRs
and pushes. Test cleanup deletes only its own resources.

Additional local checks used all image-based services from the resolved Compose
configuration, public `.env.example` credentials, isolated volumes and random
ports. They covered PostgreSQL initialization, Redis writes, durable RabbitMQ
publishing, S3 bucket provisioning/read/write/access denial, Keycloak import/token/
login theme, monitoring readiness, Prometheus query, Grafana provisioning/backend
health, Alloy-to-Loki structured log ingestion, and persistence after restarting
PostgreSQL, Redis, RabbitMQ, SeaweedFS and Keycloak.

Not covered: migration of an existing developer's databases or Grafana state,
72-hour retention expiry, load/performance tests, browser OIDC login/logout,
full upload-to-analysis flow with real ML weights, and manual GHCR image publishing.
Backups and a rehearsal on copies of existing volumes remain necessary before
upgrading an installation with valuable state. The GitHub image scan blocks reported CRITICAL vulnerabilities and publishes
all HIGH findings, including unfixed ones. See [the security audit](infrastructure-security-audit.md)
for the remaining findings; passing CI does not mean there are zero reported CVEs.

## Security rebuilds

`postgres` and `keycloak-db` share a build based on PostgreSQL 18.6/Alpine 3.24.
It removes the upstream `gosu` binary, which embeds an outdated Go runtime, and
uses Alpine's `su-exec` in the official entrypoint. Fresh-volume initialization,
least-privilege database bootstrap and persistence after restart were checked
with the existing capabilities and read-only filesystem settings.

The Keycloak config CLI is built from upstream v6.5.1, pinned by commit and archive
checksum, with patched Spring Boot/Framework, Jackson 2/3, Netty and RESTEasy
libraries. It runs as UID 65534 on a pinned Temurin 21 Alpine runtime. The actual
realm import, client credentials token and custom theme are covered by the smoke
checks. Alloy uses a pinned upstream 1.20.1 base with the patched Ubuntu OpenSSL
packages; Docker discovery and Loki structured metadata ingestion were checked.
Build these services when updating: `docker compose --profile core --profile auth
--profile monitoring build postgres keycloak-db keycloak-config-cli alloy`.

Run `python3 infra/tests/test_keycloak.py` for the isolated realm import and
password-reset regression test. Its SMTP sink never sends mail outside the test
network. The test verifies that a forced reset flow cannot change the password
without consuming the emailed action token. As a negative control,
`KEYCLOAK_TEST_IMAGE=quay.io/keycloak/keycloak:26.7.1 python3
infra/tests/test_keycloak.py` fails at the upstream reset-flow regression assertion;
the configured 26.8.0 image passes. These tests leave existing developer volumes
and realms untouched.
