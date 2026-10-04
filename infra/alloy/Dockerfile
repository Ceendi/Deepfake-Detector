FROM grafana/alloy:v1.20.1@sha256:2aa2099af76c0098d4af7a4d6e48f86cb66dc1a000222ad927a1c67c6542d13f
USER root
# Patch CVE-2026-84782 while the upstream image still ships the old OpenSSL.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libssl3t64=3.0.13-0ubuntu3.16 openssl=3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/lib/alloy/data \
    && chown -R 65534:65534 /var/lib/alloy

USER 65534:65534
