FROM grafana/alloy:v1.21.3@sha256:8ab871206bd2d8f89277f53abffee5f452a293980788d52f85fa183a87a505a4
USER root
# Patch CVE-2026-84782 while the upstream image still ships the old OpenSSL.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libssl3t64 openssl \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libssl3t64)" ge 3.0.13-0ubuntu3.16 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' openssl)" ge 3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/lib/alloy/data \
    && chown -R 65534:65534 /var/lib/alloy

USER 65534:65534
