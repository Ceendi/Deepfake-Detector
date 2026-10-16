# D4 load test — File Service upload (1 vs 2 replicas)

Proves D4: replicating `file-service` behind the Gateway's Eureka load balancer improves throughput / p95.
50 concurrent threads × 60s, ~5 MB multipart upload through the Gateway (`lb://file-service`).
Production defaults are 5 uploads/minute (burst 5), 4 concurrent uploads per replica,
2 concurrent uploads per user on each replica and a 5 GiB quota per user.
The isolated benchmark raises these limits below; all 50 threads share one token.

**Tools:** Apache JMeter 5.6.x (on PATH), ffmpeg (sample), Docker. Report lands in
[`docs/reports/d4-replication.md`](../../docs/reports/d4-replication.md).

## 0. One-time prep

```powershell
# Valid ~5 MB MP4 (random blobs get a 422 from Tika+ffprobe and never reach S3).
.\generate-sample.ps1            # or ./generate-sample.sh

# Opt in to the development realm and load-test client before starting auth.
$env:KEYCLOAK_REALM_CONFIG = "dev/realm-export.json"
# .env must define LOADTEST_CLIENT_SECRET (see .env.example). Export it for the runner too:
$env:LOADTEST_CLIENT_SECRET = "<same as .env>"
```

## 1. Variant 1 — replicas=1

```powershell
# Rate-limit raised so the per-user bucket doesn't throttle 50 threads on one token.
$env:UPLOAD_RL_REPLENISH = "10000"; $env:UPLOAD_RL_BURST = "10000"
$env:UPLOAD_RL_REQUESTED_TOKENS = "1"
$env:UPLOAD_MAX_CONCURRENT = "50"
$env:UPLOAD_MAX_CONCURRENT_PER_USER = "50"
# Isolated benchmark only: permit the generated uploads to exceed the normal 5 GiB quota.
$env:STORAGE_QUOTA_BYTES = "536870912000"
$env:FILE_SERVICE_REPLICAS = "1"
docker compose --profile core --profile auth up -d --build
```

Wait for warmup, then **verify** before measuring:
- `docker compose exec gateway wget -qO- http://eureka-server:8761/eureka/apps`
  → exactly **1** `FILE-SERVICE` instance (Eureka has no published host port).
- One manual upload returns **200** (not 422/401/429) — e.g. via the runner with `-Threads 1 -Duration 5`.
- The first full run after a fresh `up` is slow (cold JIT, SeaweedFS creates its volumes). Run it once with
  `-Label warmup` and discard it.

```powershell
.\run-loadtest.ps1 -Label r1
```

## 2. Variant 2 — replicas=2

```powershell
$env:FILE_SERVICE_REPLICAS = "2"
docker compose --profile core --profile auth up -d --no-recreate --scale file-service=2 file-service
```

**Wait ~45–60s** (Eureka registration + Gateway LB cache), then verify:
- `docker compose exec gateway wget -qO- http://eureka-server:8761/eureka/apps`
  → **2** `FILE-SERVICE` instances (distinct IPs).
- A few warmup uploads hit **both** replicas: `docker compose logs file-service | Select-String "/api/files/upload"` shows two container IDs.
- Use `--no-recreate` for a count-only change: the existing healthy replica keeps serving while
  Compose starts the second. A plain `up -d` after changing `deploy.replicas` can recreate all
  replicas and expose `503 No servers available` until discovery refreshes. Do a full warm-up
  run (`-Label warmup`) and measure only after it ends with 0 % errors.

```powershell
.\run-loadtest.ps1 -Label r2
```

### Discovery and count-only scaling

The gateway does not override `eureka.client.registry-fetch-interval-seconds` (30 s),
and Spring Cloud LoadBalancer's cache defaults to 35 s. Eureka's server response cache
also defaults to a 30 s update interval. These are independent caches, so 45–60 s is a
warm-up guideline, not an availability deadline. Confirm actual registry contents and
successful requests before recording results.
[Eureka properties](https://docs.spring.io/spring-cloud-netflix/reference/configprops.html),
[LoadBalancer cache](https://docs.spring.io/spring-cloud-commons/reference/spring-cloud-commons/loadbalancer.html#loadbalancer-caching).

Compose `up` has no rolling-update controller; `deploy.update_config` is not a rolling
deployment guarantee for local Compose. The command above uses
[`--no-recreate`](https://docs.docker.com/reference/cli/docker/compose/up/) only when the
replica count changes. It does not apply image, credentials or configuration updates to
existing containers. Do not use it for those updates. Scale down only after the measured
traffic has stopped; a removed replica can still be present in discovery caches and may
have in-flight requests. This procedure reduces the scale-up outage, but promises no
zero-downtime deployment or scale-down.

There is no Gateway `Retry` filter in these routes. LoadBalancer client retry settings
alone do not replay a Gateway exchange. A possible follow-up is to remove the duplicate
LB cache for Eureka, shorten registry refresh and evaluate a route-specific retry for
GET/HEAD, with a real connection-failure test. Leave those defaults unchanged until that
experiment: retries can multiply load and cannot fix an empty registry. Never retry the
POST multipart upload automatically (a lost response can hide an accepted upload).
`infra/tests/test_core_reliability.py` checks real scrapes and preserves container IDs
across count-only scaling with `--no-recreate`.

## 3. Compare

Open `report-r1/index.html` and `report-r2/index.html` (throughput + response-time graphs), or read
`results-r1.jtl` / `results-r2.jtl`. Fill the table + charts in the report. Criterion: improvement in ≥1 metric.

## Knobs (all `-J` properties, defaults in brackets)

`host`[localhost] `port`[8080] `threads`[50] `duration`[60] `rampup`[5] `kcHost`[localhost]
`kcPort`[8180] `sampleFile`[sample-5mb.mp4] `loadtestSecret`(required).

## Notes / validity

- Keep **identical** conditions across both runs (same sample, same `-J` params, fresh `up`, same warmup) — the only variable is `FILE_SERVICE_REPLICAS`.
- **Windows:** exclude `%LOCALAPPDATA%\Docker\wsl` and the `vmmem`/`vmmemWSL` processes from Defender real-time
  scanning (admin PowerShell: `Add-MpPreference -ExclusionPath "$env:LOCALAPPDATA\Docker\wsl"`). Without it the Docker
  disk flushed at ~106 MB/s instead of ~730 MB/s and one-replica upload throughput was 3.0–4.3 req/s instead of 7.0 (see the D4 report).
- Client (JMeter) and server share the host → absolute numbers are noisy, but the 1-vs-2 comparison is consistent.
- Each `file-service` replica is capped at 1 vCPU (compose `deploy.resources.limits`), so the 2nd replica genuinely ~doubles the CPU-bound (ffprobe+Tika) upload capacity.
- The `ml` profile is not needed (upload doesn't touch detectors); `monitoring` is optional (Grafana panels for live view).
- Artifacts (`*.mp4`, `*.jtl`, `report-*/`) are git-ignored.
