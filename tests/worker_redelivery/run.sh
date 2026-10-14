#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
project="pr06-worker-${GITHUB_RUN_ID:-local}-$$"
compose=(docker compose -p "$project" -f "$root/tests/worker_redelivery/compose.yml")
cleanup() {
  status=$?
  # Keep broker/Redis diagnostics: a fixture that dies at startup is otherwise invisible.
  if [ "$status" -ne 0 ]; then
    "${compose[@]}" ps -a || true
    "${compose[@]}" logs --no-color --tail 200 || true
  fi
  "${compose[@]}" down -v --remove-orphans
}
trap cleanup EXIT
"${compose[@]}" up -d --wait --wait-timeout 180
export WORKER_TEST_COMPOSE_PROJECT="$project"
python="${DETECTOR_TEST_PYTHON:-python3}"
RABBITMQ_PORT=$("${compose[@]}" port rabbit 5672 | sed 's/.*://')
RABBITMQ_UI_PORT=$("${compose[@]}" port rabbit 15672 | sed 's/.*://')
REDIS_PORT=$("${compose[@]}" port redis 6379 | sed 's/.*://')
export RABBITMQ_PORT REDIS_PORT
# Workers run as the production `detector` user; the harness keeps the `test` administrator.
RABBITMQ_ORCHESTRATOR_PASSWORD=$("$python" -c 'import secrets; print(secrets.token_hex(16))')
RABBITMQ_DETECTOR_PASSWORD=$("$python" -c 'import secrets; print(secrets.token_hex(16))')
export RABBITMQ_ORCHESTRATOR_PASSWORD RABBITMQ_DETECTOR_PASSWORD
export RABBITMQ_MANAGEMENT_URL="http://127.0.0.1:$RABBITMQ_UI_PORT" RABBITMQ_ADMIN_USER=test RABBITMQ_ADMIN_PASSWORD=test
"$python" -I "$root/infra/rabbitmq/provision_users.py"
export RABBITMQ_HOST=127.0.0.1 RABBITMQ_USER=detector RABBITMQ_PASSWORD="$RABBITMQ_DETECTOR_PASSWORD"
export REDIS_HOST=127.0.0.1 AWS_EC2_METADATA_DISABLED=true
"$python" -m pytest -q "$root/tests/worker_redelivery/test_redelivery.py" \
  "$root/tests/worker_redelivery/test_broker_permissions.py" "$@"
