#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
project="pr06-worker-${GITHUB_RUN_ID:-local}-$$"
compose=(docker compose -p "$project" -f "$root/tests/worker_redelivery/compose.yml")
cleanup() { "${compose[@]}" down -v --remove-orphans; }
trap cleanup EXIT
"${compose[@]}" up -d --wait --wait-timeout 180
export WORKER_TEST_COMPOSE_PROJECT="$project"
export RABBITMQ_HOST=127.0.0.1 RABBITMQ_USER=test RABBITMQ_PASSWORD=test
export REDIS_HOST=127.0.0.1 AWS_EC2_METADATA_DISABLED=true
RABBITMQ_PORT=$("${compose[@]}" port rabbit 5672 | sed 's/.*://')
REDIS_PORT=$("${compose[@]}" port redis 6379 | sed 's/.*://')
export RABBITMQ_PORT REDIS_PORT
"${DETECTOR_TEST_PYTHON:-python3}" -m pytest -q "$root/tests/worker_redelivery/test_redelivery.py"
