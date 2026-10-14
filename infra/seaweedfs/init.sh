#!/bin/sh
# Render and atomically publish the S3 identities and JWT signing keys.
set -eu
exec python3 -I /render_config.py "$@"
