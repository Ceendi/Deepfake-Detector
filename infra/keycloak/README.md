# Keycloak realm

`realm-export.json` is auto-imported by `start-dev --import-realm` on first
boot (see `docker-compose.yml`). It defines the `deepfake` realm with one
public client (`deepfake-web`, PKCE).

## Password policy

Currently enforced:

- min length 10
- not username, not email
- ≥1 digit, ≥1 uppercase, ≥1 lowercase, ≥1 special char
- last 3 passwords cannot be reused

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
data; refresh-token rotation handles the UX.
