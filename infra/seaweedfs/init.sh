#!/bin/sh
# Render and atomically publish the S3 identity configuration.
set -eu
exec python3 -I /render_config.py "$@"
