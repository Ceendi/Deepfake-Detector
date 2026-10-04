# Infrastructure dependency security audit

Verified on 2026-10-04 with Trivy 0.75.0 and its vulnerability database updated
`2026-10-03T19:02:38Z`. The database refresh returned the same current snapshot.
The final local scan used the actual Linux/ARM64 runtime images, including all
three locally rebuilt infrastructure images. The CI matrix separately builds and
scans Linux/AMD64 infrastructure images and uploads every JSON result.

No ignore file, `ignore-unfixed` filter or vulnerability suppression is used.
Counts are package/advisory occurrences, not unique CVE IDs.

| Image | CRITICAL | HIGH | Without a listed fix |
| --- | ---: | ---: | ---: |
| `alpine:3.24.2` | 0 | 0 | 0 |
| `amazon/aws-cli:2.37.9` | 0 | 0 | 0 |
| `chrislusf/seaweedfs:4.48` | 0 | 0 | 0 |
| `deepfake-alloy:1.20.1-security` | 0 | 0 | 0 |
| `deepfake-keycloak-config-cli:6.5.1-security` | 0 | 0 | 0 |
| `deepfake-postgres:18.6-alpine3.24` | 0 | 0 | 0 |
| `grafana/grafana:13.1.7` | 0 | 2 | 0 |
| `grafana/loki:3.7.8` | 0 | 0 | 0 |
| `grafana/tempo:3.1.0` | 0 | 0 | 0 |
| `prom/prometheus:v3.15.0` | 0 | 0 | 0 |
| `quay.io/keycloak/keycloak:26.8.0` | 0 | 6 | 6 |
| `rabbitmq:4.3.6-management-alpine` | 0 | 0 | 0 |
| `redis:8.10.2-alpine` | 0 | 0 | 0 |

## Remaining findings

Keycloak contains PCRE2 `10.40-6.el9`. Trivy reports HIGH CVE-2026-103111,
CVE-2026-86145 and CVE-2026-89161 in both `pcre2` and `pcre2-syntax` (six
occurrences). Red Hat lists RHEL 9 PCRE2 as affected with no published fix for
these packages. The newer Hardened Images advisory is for a different product;
it is not a patch for the UBI 9 packages in this Keycloak image.
These are native regular-expression/JIT memory-safety issues, not the Java
password-reset vulnerability. No endpoint accepting native PCRE2 expressions
was identified in this configuration; this is a limited reachability assessment,
not a claim that the library is safe. Keep these findings visible and update the
official Keycloak/UBI packages when a supported vendor fix becomes available.

Sources: [Red Hat PCRE2 CVE status](https://access.redhat.com/security/cve/CVE-2026-103111),
[PCRE2 out-of-bounds write](https://bugzilla.redhat.com/show_bug.cgi?id=CVE-2026-86145),
[PCRE2 JIT memory corruption](https://bugzilla.redhat.com/show_bug.cgi?id=2531884).

Grafana 13.1.7 has two HIGH module-version matches for CVE-2026-21728 and
CVE-2026-28377 against `github.com/grafana/tempo` pseudo-version
`v1.5.1-0.20260427112133-525d1bab07e0`. Both patches are ancestors of that
embedded commit: query-limit fix `650eb1985a0776789c8564122990f588a742356f`
and SSE-C secret fix `bb8ca663db34a0980c9758b40d918fda3b4dbec3`.
The pseudo-version sorts below the advisories' fixed 2.x release versions;
these two matches do not describe unpatched code in this embedded revision.
They remain in the raw scan output. The independently running Tempo 3.1.0
image has zero HIGH/CRITICAL findings.

Sources: [query-limit patch ancestry](https://github.com/grafana/tempo/compare/650eb1985a0776789c8564122990f588a742356f...525d1bab07e0),
[SSE-C patch ancestry](https://github.com/grafana/tempo/compare/bb8ca663db34a0980c9758b40d918fda3b4dbec3...525d1bab07e0),
[embedded secret field](https://github.com/grafana/tempo/blob/525d1bab07e0/tempodb/backend/s3/config.go).

## Verification and scope

The complete isolated 14-service infrastructure stack passed database/bootstrap,
Redis, RabbitMQ, S3 permissions, identity import/token/theme, datasource health,
Prometheus query, Alloy-to-Loki metadata and persistent-state restart checks.
Two Tempo integration tests cover ingestion/restart and a flushed Tempo 2
historical trace. The Keycloak reset regression passes on 26.8.0 and fails on
the vulnerable 26.7.1 control. Two report tests cover mixed-target critical and
unfixed findings and empty/null vulnerability lists.

Not covered: real ML inference/training, existing developer volume migrations,
72-hour retention expiry, performance/load tests, browser OIDC login/logout and
manual GHCR publishing. Application Maven/npm/Python dependencies are handled
in a separate security change; this image matrix does not build the ML or Java
application Dockerfiles.
