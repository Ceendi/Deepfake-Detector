# Keycloak realm

`realm-export.json` is the **declared state** of the `deepfake` realm.
[`keycloak-config-cli`](https://github.com/adorsys/keycloak-config-cli) syncs it into the running
Keycloak on every `docker compose up` with the `auth` profile; see
[ADR-0001](../../docs/adr/0001-keycloak-realm-as-code.md) for the reasoning and upgrade risks.

## What's in the realm

- One public client, `deepfake-web` (PKCE S256), for the SPA; open registration.
- Realm role `USER`, granted to every new user through `default-roles-deepfake`.
- No fixture users or service-account clients in the default import.
- Password policy, brute-force protection and token lifetimes (below).

## Local smoke tests and load tests

`dev/realm-export.json` is a complete development variant of the same realm. It adds `alice` and
`bob` (password `Test1234!@`) and the confidential `deepfake-loadtest` client with a USER service
account; its secret comes from `LOADTEST_CLIENT_SECRET`. Its policy and SPA client must match
`realm-export.json`, which `infra/tests/test_security_configuration.py` checks. To opt in, put
`KEYCLOAK_REALM_CONFIG=dev/realm-export.json` in your local `.env` and run:

```bash
docker compose --profile auth up -d keycloak-config-cli
```

Compose mounts the config directory read-only and imports only the selected file. Keep
`KEYCLOAK_REALM_CONFIG` unset in deployments.

**Leaving the dev variant:** switching back removes the load-test client, but `alice` and `bob`
stay (users are `no-delete`, see below) and keep their passwords. Before using a dev database in a
deployment, delete the two users and check through the Admin API that the load-test client and its
service account are gone. Registered users are kept; no volume reset is needed.

### Allowed clients

File-service and orchestrator accept only tokens of clients in `JWT_ALLOWED_CLIENTS` (default
`deepfake-web,deepfake-loadtest`). They check `azp`; only when `azp` is absent must one audience be
allowed. A foreign, empty or malformed `azp` is rejected (`401`) even with an allowed audience, and
signature, issuer, expiry, roles and ownership are still checked. Adding a client to the realm does
not grant API access: also add it to `JWT_ALLOWED_CLIENTS` of both services.

## Changing the realm

Edit the JSON, commit, and apply it:

```bash
docker compose --profile auth up keycloak-config-cli   # exits 0 when the realm matches the file
```

The CLI connects as the bootstrap admin (`KEYCLOAK_ADMIN`), diffs declared and actual state and
applies only the difference; re-runs are no-ops. It never wipes and reimports the realm, so
sessions and registered users survive.

| Resource | Mode | Effect |
|---|---|---|
| roles, clients, authentication flows | `full` | The file is the only source of truth; changes made in the Admin UI are removed on the next sync. |
| users | `no-delete` | Users declared in the file are reconciled (including their password); users created elsewhere are kept. |

With the dev variant, `alice` and `bob` get their password reset to `Test1234!@` on every sync, so
scripted tests start from a known state. **Rule:** change roles, clients and policies only in the
JSON, never in the Admin UI.

The `keycloak-config-cli` image is v6.5.1 built from the pinned upstream source with patched
dependencies; the source archive is fetched from `codeload.github.com` by commit and SHA-256.
`infra/tests/test_keycloak.py` checks both imports and the password-reset flow.

## Policies

- **Passwords:** at least 10 characters with a digit, an upper-case letter, a lower-case letter and
  a special character; not the username or email. Argon2id hashing (Keycloak default). No password
  history and no forced rotation (NIST SP 800-63B advises against expiry-based rotation).
- **Brute force:** 10 failed logins lock the account, with an exponential wait of up to 15 min
  (`failureFactor`, `waitIncrementSeconds`, `maxFailureWaitSeconds`).
- **Tokens:** access token 15 min, SSO session 30 min idle / 10 h maximum, deliberately short for
  biometric data. The SPA refreshes access tokens within the session; refresh-token rotation is
  not configured.

## Email verification and password reset — disabled

The dev stack has no SMTP server, so email verification is off and both realm variants set
`resetPasswordAllowed: false` (no "Forgot password" link). `infra/tests/test_keycloak.py` checks
that reset stays disabled and exercises the reset flow with a local mail sink in a disposable
realm. To enable them, add an `smtpServer` block (credentials from environment variables, e.g.
`${SMTP_PASSWORD}`), set `verifyEmail: true`, add `VERIFY_EMAIL` to the default actions and set
`resetPasswordAllowed: true` in both JSON variants. For development a mail sink such as Mailpit is
enough.
