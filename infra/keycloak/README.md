# Keycloak realm

`realm-export.json` is the **declared state** of the `deepfake` realm. It is
synced into a running Keycloak by [`keycloak-config-cli`](https://github.com/adorsys/keycloak-config-cli)
on every `docker compose --profile auth up` — see [ADR-0001](../../docs/adr/0001-keycloak-realm-as-code.md).

## What's in the realm

- One public client (`deepfake-web`, PKCE S256) for the SPA
- Realm role `USER`, granted by default to every newly-registered user via the
  composite meta-role `default-roles-deepfake`
- No fixture users or service-account clients in the default import
- Password policy, brute-force protection, token lifespans (details below)

## Local smoke tests and load tests

Test identities live in `dev/realm-export.json`, a complete development variant
of the same realm. Its policy and SPA client must match `realm-export.json`;
`infra/tests/test_security_configuration.py` checks this to prevent drift.
It adds `alice` and `bob` (password `Test1234!@`) and the confidential
`deepfake-loadtest` client with its USER service account. The client secret
comes from `LOADTEST_CLIENT_SECRET`; use these identities only locally.

The default `.env.example` keeps this variant disabled. To opt in, put
`KEYCLOAK_REALM_CONFIG=dev/realm-export.json` in your local `.env`, then run:

```bash
docker compose --profile auth up -d keycloak-config-cli
```

Compose mounts the config directory read-only and imports only the selected
file, never a wildcard. Keep `KEYCLOAK_REALM_CONFIG` unset in deployments.

**Existing installations:** switching back to the default import removes the
managed load-test client, but `IMPORT_MANAGED_USER=no-delete` preserves existing
users, including previously imported `alice` and `bob`. Removing the dev option
alone therefore does not revoke those passwords. Before using an existing dev
database in a deployment, remove the two fixture users and verify the load-test
client and service account are absent through the Keycloak Admin API. Preserve
real registered users; no volume reset is needed.

## Update flow — how to change realm config

The realm is **idempotent IaC** — edit the JSON, commit, and the next compose
up applies the diff. Concretely:

```bash
# 1. Edit infra/keycloak/realm-export.json
# 2. Restart the sync container (or run a full up)
docker compose --profile auth up keycloak-config-cli
# Exits 0 when the realm matches the file. Re-runs are no-ops.
```

`keycloak-config-cli` connects to KC's Admin REST API as the bootstrap admin
(`KEYCLOAK_ADMIN` from `.env`), diffs declared vs actual, and applies the
delta. It does **not** wipe and reimport — existing user sessions, audit logs,
and manually-created users are preserved (see Managed modes below).

### What happens to teammates who pulled an older branch

The previous setup used `start-dev --import-realm`, which imported the realm
only on first boot (when DB was empty). After `git pull` of this branch, run:

```bash
docker compose --profile auth up keycloak-config-cli
```

This applies the new declared state to your existing realm. **No volume drop
is required** — that's the entire point of switching to kc-config-cli. Users
you created manually for testing survive.

## Managed modes (resource ownership)

| Resource          | Mode        | Effect                                                                 |
|-------------------|-------------|------------------------------------------------------------------------|
| roles, clients, authentication flows | `full` (default) | File is the sole source of truth. UI additions get purged on next sync. |
| users             | `no-delete` | File-declared users are reconciled (incl. password reset). UI-created users are preserved. |

With the development variant enabled, **dev users `alice` and `bob` have their passwords reset to
`Test1234!@` on every sync** — that's deliberate, gives the team a known good
state for scripted tests. If you want a dev account whose password sticks,
create it via Admin UI; `no-delete` will leave it alone.

**Rule for the team:** for anything other than ad-hoc throwaway users (roles,
clients, password policy, etc.), edit the JSON. Admin UI changes to managed
resources will silently disappear on the next sync.

## Compatibility note

Compose builds `deepfake-keycloak-config-cli:6.5.1-security` from the pinned
upstream source with patched dependencies and tests it against Keycloak `26.8.0`.
The archive is fetched directly from `codeload.github.com` by commit and SHA-256;
building does not spend the unauthenticated GitHub REST API quota. The source commit
and archive checksum are unchanged.
The integration test verifies both the default import and the development
variant, then verifies reset is disabled without SMTP and exercises the password-reset flow
with SMTP enabled only in the disposable test realm. See [ADR-0001](../../docs/adr/0001-keycloak-realm-as-code.md) for
the full risk analysis and upgrade plan.

## Password policy

Currently enforced:

- min length 10
- not username, not email
- ≥1 digit, ≥1 uppercase, ≥1 lowercase, ≥1 special char

Password history is not configured (`passwordHistory` is absent).

Hash algorithm is Argon2id (Keycloak 26 default) — no `hashIterations(...)`
override needed.

**No forced password rotation** — NIST SP 800-63B (rev 4) explicitly recommends
against arbitrary expiry-based rotation: users adapt by appending counters
(`!1`, `!2`, …) which weakens passwords. Strong length + brute-force lockout +
optional 2FA is the modern stance; PCI DSS 4.0 and ISO 27002:2022 align.

## Email verification — DISABLED

Verification + SMTP are intentionally omitted because there's no SMTP server
in the dev stack. To enable later:

1. Add `verifyEmail: true` at realm root.
2. Add an `smtpServer` block:
   ```json
   "smtpServer": {
     "host": "smtp.example.com",
     "port": "587",
     "from": "noreply@deepfake.local",
     "auth": "true",
     "user": "${SMTP_USER}",
     "password": "${SMTP_PASSWORD}",
     "starttls": "true"
   }
   ```
3. Add `VERIFY_EMAIL` to `defaultActions` for new users.

For dev, MailHog or Mailpit in a separate compose profile is the usual
pattern (no real outbound mail).

## Brute-force protection

Enabled (`bruteForceProtected: true`): 10 failed logins → exponential lockout
up to 15 min. Tweak `failureFactor` and `waitIncrementSeconds` per
deployment.

## Token lifetimes

- `accessTokenLifespan`: 15 min
- `ssoSessionIdleTimeout`: 30 min
- `ssoSessionMaxLifespan`: 10 h

These are deliberately short for a project handling biometric (face/voice)
data. The SPA refreshes access tokens within the session. Refresh-token revocation/rotation
is not configured (`revokeRefreshToken` defaults to `false`); the README does not
promise single-use refresh tokens.

## Password reset — DISABLED

Both realm variants set `resetPasswordAllowed: false`, hiding “Forgot password”
until SMTP is available. Enable it in both JSON variants only after configuring
SMTP. `infra/tests/test_keycloak.py` verifies the default and development imports
keep reset disabled, then enables it with a local mail sink to retain the existing
password-reset security regression.
