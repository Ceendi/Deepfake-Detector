# Infisical — quick start

Secrets for the dev stack come from Infisical. If you don't want an account,
use the `.env` fallback (see [README](../README.md)).

## 1. Get invited

Sign up at <https://app.infisical.com>, ask the project lead to invite you
to the `deepfake-detector` workspace.

## 2. Install the CLI

```bash
# macOS
brew install infisical/get-cli/infisical

# Linux (Debian/Ubuntu)
curl -1sLf \
'https://artifacts-cli.infisical.com/setup.deb.sh' \
| sudo -E bash

# Windows
winget install infisical

# any OS — npm
npm install -g @infisical/cli
```

## 3. Log in

```bash
infisical login
```

## 4. Verify

```bash
infisical secrets --env=dev
```

Should list the dev secrets. If empty / 401 → you're not invited yet.

## 5. Run the stack

```bash
infisical run --env=dev -- docker compose --profile core --profile auth up -d
```

## Adding a new secret

Add it in the Infisical UI for **every environment** AND to
[`.env.example`](../.env.example) with a placeholder, so the `.env` fallback
keeps working.
