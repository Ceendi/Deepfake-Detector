# ADR-0001: Keycloak realm as code via keycloak-config-cli

- **Status:** Accepted
- **Date:** 2026-05-15
- **Deciders:** Backend lead (Osoba 1)
- **Supersedes:** initial `start-dev --import-realm` bootstrap (commits up to
  `a9d37fc`)

## Context

The `deepfake` Keycloak realm holds authoritative auth config: clients, roles,
password policy, token lifespans, brute-force settings, dev users. It is
checked into `infra/keycloak/realm-export.json` so that fresh-start
environments (`docker compose down -v && up`) come up with a working auth
plane without manual setup.

Up to and including the walking-skeleton merge, the realm was bootstrapped by
Keycloak's built-in `start-dev --import-realm` flag. This has a well-known
limitation:

> `--import-realm` imports the file only if the realm does **not** yet exist
> in the database. Once imported, the file is **ignored on every subsequent
> start**. The DB is the source of truth.

In a four-person team where the realm export will change several times per
semester (USER role next sprint, test clients later, possibly group mappers),
this forces every edit to be paired with a destructive `docker volume rm
keycloak-pgdata` across all developers — losing manually-created test users,
sessions, and admin-side experiments. The friction is small per change but
multiplies across team-size × change-count, and it normalizes "blow it all
away" as a config-update workflow.

We want a config-as-code workflow for the realm: edit the JSON, commit, and
have the live realm converge to the declared state idempotently, without
clobbering ad-hoc UI work.

## Decision

Adopt **[`adorsys/keycloak-config-cli`](https://github.com/adorsys/keycloak-config-cli)**
as the realm sync mechanism, pinned at `6.5.0-26.5.4`. It runs as a one-shot
container in the `auth` compose profile, after Keycloak reports healthy.

Key configuration:

- `IMPORT_MANAGED_USER=no-delete` — file-declared users (`alice`, `bob`) are
  reconciled (password reset on every sync, deliberate for known-state dev
  tests); users created manually in the Admin UI are preserved.
- All other managed types (`role`, `client`, `authenticationFlow`, `group`,
  etc.) remain at the default `full` — the JSON is the sole source of truth.
- Auth: bootstrap admin user (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD`
  from `.env`). Dedicated service account is not introduced — see
  alternatives below.
- Keycloak container loses the `--import-realm` flag and the realm.json bind
  mount; kc-config-cli owns the import path end-to-end.

## Consequences

### Positive

- **Editing the realm no longer destroys local state.** Pull branch, run
  `docker compose --profile auth up keycloak-config-cli`, the realm converges.
  Cumulative team-time saved: roughly 4 people × 3 min × ~5 realm changes in
  semester 1 ≈ 1 person-hour.
- **PR review of realm changes is meaningful.** Reviewers see the JSON diff
  and can reason about it directly. Previously the JSON change was paired
  with a `volume rm` reset, so the JSON was rarely scrutinized in detail.
- **Production-ready pattern.** The same container, with `KEYCLOAK_URL`
  pointing at a managed Keycloak (e.g. behind a load balancer in semester-2
  prod compose), syncs declared state into staging/prod identically. No
  rewrite needed.
- **Defensible architecture choice on the diploma defense.** "We use IaC for
  identity provider configuration with idempotent reconciliation" is a senior
  engineering practice; it shows we understand the cost of click-ops on auth
  plane.

### Negative / accepted tradeoffs

- **Forward-version mismatch.** kc-config-cli `6.5.0` was built/tested
  against Keycloak `26.5.4`; our stack runs `26.6.1`. The Admin REST API
  surface we exercise (realms, roles, users, clients, password policy) is
  stable across `26.5 → 26.6` minor bumps — empirically verified on initial
  rollout (see test log in PR). We will bump the cli pin to `6.6.0` when it
  ships with official `26.6` support. **Upgrade trigger:** kc-config-cli
  release notes show explicit `26.6.x` testing.
- **Admin credential coupling.** The cli authenticates as the bootstrap
  admin. This couples sync tooling to the bootstrap identity — a dedicated
  service account would be cleaner. Deferred to semester 2 (see "Alternatives
  considered §2").
- **Cultural shift "don't touch managed resources in Admin UI".** A team
  member who adds a new role via the UI will see it disappear on the next
  sync (managed mode `full`). Mitigated by docs in [`infra/keycloak/README.md`](../../infra/keycloak/README.md)
  + linking from CLAUDE.md. Throwaway test users are explicitly carved out
  by `IMPORT_MANAGED_USER=no-delete`.
- **One extra container at startup.** ~145 MB image, ~256 MB heap JVM
  running for ~5–10 s on each `up`. Negligible on dev hardware (16 GB RAM
  baseline assumed per CLAUDE.md).
- **Dev users' passwords reset on every sync.** Anyone changing `alice` /
  `bob` passwords via UI will see them revert. This is intentional — known
  passwords for scripted tests. Documented in README.

## Alternatives considered

### 1. Keep `start-dev --import-realm` + document `volume rm` workflow

Status quo. Zero new dependencies, zero new containers.

**Rejected because:** it normalizes destructive workflows for config updates,
and the cumulative friction across a 4-person team over a 7-month diploma
project outweighs the 2-hour setup cost of adopting an idempotent tool.
Documented this analysis in
[the kc-config-cli evaluation discussion](#) (see chat log §"Czas — 2-4h
zależnie od trafienia w pułapki").

### 2. Dedicated service account client for kc-config-cli

Create a confidential client `kc-config-cli-sync` with `serviceAccountsEnabled:
true` and the `realm-management:realm-admin` role; pass `KEYCLOAK_CLIENTID` +
`KEYCLOAK_CLIENTSECRET` + `KEYCLOAK_GRANTTYPE=client_credentials` to the cli
instead of admin credentials.

**Deferred because:** chicken-and-egg — the service account client must exist
*before* kc-config-cli can authenticate, which means the very first sync
needs to bootstrap with admin credentials anyway. For dev, where the admin
identity is already wired in `.env`, the simpler path is admin credentials.
Revisit in semester 2 when we prepare the production compose
(`docker-compose.prod.yml`) — at that point we will rotate to a service
account because the prod admin credential should not be in any non-bootstrap
tool's environment.

### 3. Terraform Keycloak provider

`mrparkers/terraform-provider-keycloak`. True declarative IaC, plan/apply,
state diff in CI.

**Rejected because:** introduces Terraform as a build dependency for an
otherwise pure docker-compose project, requires state storage decisions
(local file? S3 backend?), and the team has no Terraform experience to
amortize the learning cost against. Overkill for a 7-month diploma scope.

### 4. Custom `kcadm.sh` bash scripts

Write shell scripts wrapping the official `kcadm.sh` CLI shipped with
Keycloak. Run as `docker compose run` one-shots.

**Rejected because:** non-idempotent by default (would need our own diff
logic), brittle to KC version upgrades (script breakage on every minor),
poor PR-review story (script intent ≠ resulting realm state). Reinventing
kc-config-cli badly.

## Operational notes

- **First sync from empty DB:** kc-config-cli creates the realm from scratch.
  Bootstrap admin is created by Keycloak itself on first start (`KC_BOOTSTRAP_ADMIN_*`).
  Sequence verified end-to-end on `feat/auth-security-layer` branch.
- **Re-sync after pull:** `docker compose --profile auth up keycloak-config-cli`.
  Exits 0; logs show applied changes (or no-op).
- **Sync failure:** non-zero exit; logs printed via the `json-file` driver
  with default 10m/3-file cap. Failure does not block Keycloak itself —
  manual intervention needed (read logs, fix file, re-run).
- **Production roadmap:** semester 2 — switch auth to service account
  (alternative §2), pin cli to `6.6.x` with official 26.6 build, optionally
  add a CI job that runs `kc-config-cli` in dry-run mode against a fresh KC
  to catch realm-export.json regressions before merge.

## References

- [keycloak-config-cli repository](https://github.com/adorsys/keycloak-config-cli)
- [Managed resources documentation](https://github.com/adorsys/keycloak-config-cli/blob/main/docs/MANAGED.md)
- [Keycloak `--import-realm` behavior](https://www.keycloak.org/server/importExport)
- This project's CLAUDE.md (§"Co NIE robić", §"Stack — wersje")
