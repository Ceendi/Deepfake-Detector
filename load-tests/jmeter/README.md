# D4 load test — File Service upload (1 vs 2 replicas)

Shows that replicating `file-service` behind the gateway's Eureka load balancer
(`lb://file-service`) improves throughput / p95: 50 concurrent threads × 60 s of ~5 MB multipart
uploads through the gateway, all on one token. The production limits (5 uploads/min, 4 concurrent
uploads per replica, 2 per user, 5 GiB quota) are raised for the benchmark below.

**Tools:** Apache JMeter 5.6.x on PATH, ffmpeg (sample), Docker. Results:
[`docs/reports/d4-replication.md`](../../docs/reports/d4-replication.md).

## 0. One-time prep

```powershell
# Valid ~5 MB MP4 (random bytes get 422 from Tika + ffprobe and never reach S3).
.\generate-sample.ps1            # or ./generate-sample.sh

# Development realm with the load-test client; LOADTEST_CLIENT_SECRET as in .env.
$env:KEYCLOAK_REALM_CONFIG = "dev/realm-export.json"
$env:LOADTEST_CLIENT_SECRET = "<same as .env>"
```

## 1. Variant 1 — one replica

```powershell
# Limits raised so one token can drive 50 threads; benchmark only.
$env:UPLOAD_RL_REPLENISH = "10000"; $env:UPLOAD_RL_BURST = "10000"
$env:UPLOAD_RL_REQUESTED_TOKENS = "1"
$env:UPLOAD_MAX_CONCURRENT = "50"
$env:UPLOAD_MAX_CONCURRENT_PER_USER = "50"
$env:STORAGE_QUOTA_BYTES = "536870912000"
$env:FILE_SERVICE_REPLICAS = "1"
docker compose --profile core --profile auth up -d --build
```

Before measuring, verify:

- `docker compose exec gateway wget -qO- http://localhost:9090/actuator/health/readiness` → `UP`
  (readiness waits for the Redis limiter warm-up; the container healthcheck only checks liveness).
- `docker compose exec gateway wget -qO- http://eureka-server:8761/eureka/apps` → exactly **1**
  `FILE-SERVICE` instance.
- One upload returns **200** (`.\run-loadtest.ps1 -Threads 1 -Duration 5`).
- Run once with `-Label warmup` and discard it (cold JIT, SeaweedFS creates its volumes).

```powershell
.\run-loadtest.ps1 -Label r1
```

## 2. Variant 2 — two replicas

```powershell
$env:FILE_SERVICE_REPLICAS = "2"
docker compose --profile core --profile auth up -d --no-recreate --scale file-service=2 file-service
```

`--no-recreate` keeps the healthy replica serving while the second starts; a plain `up -d` can
recreate both and return `503 No servers available` until discovery refreshes. Use it only for a
count-only change, never to apply new images or configuration.

Wait **45–60 s** (Eureka registry fetch 30 s, LoadBalancer cache 35 s and Eureka's response cache
are independent), then verify that Eureka lists **2** `FILE-SERVICE` instances and that warm-up
uploads reach both (`docker compose logs file-service | Select-String "/api/files/upload"` shows
two containers). Measure only after a warm-up run with 0 % errors:

```powershell
.\run-loadtest.ps1 -Label r2
```

The gateway has no `Retry` filter, so a request routed to a vanished replica is not replayed; the
upload `POST` must never be retried automatically. Scale down only after the traffic has stopped.

## 3. Compare

Open `report-r1/index.html` and `report-r2/index.html` (throughput and response-time graphs) or read
`results-r1.jtl` / `results-r2.jtl`, then fill in the report. Criterion: improvement in at least one
metric.

## Knobs (`-J` properties, defaults in brackets)

`host`[localhost] `port`[8080] `threads`[50] `duration`[60] `rampup`[5] `kcHost`[localhost]
`kcPort`[8180] `sampleFile`[sample-5mb.mp4] `loadtestSecret`(required).

## Measurement conditions

- Keep both runs identical (sample, `-J` parameters, fresh `up`, warm-up); the only variable is
  `FILE_SERVICE_REPLICAS`.
- **Windows:** exclude `%LOCALAPPDATA%\Docker\wsl` and the `vmmem`/`vmmemWSL` processes from
  Defender real-time scanning (admin PowerShell:
  `Add-MpPreference -ExclusionPath "$env:LOCALAPPDATA\Docker\wsl"`). Without it the Docker disk
  flushed at ~106 MB/s instead of ~730 MB/s and one replica managed 3.0–4.3 instead of 7.0 req/s.
- JMeter and the stack share the host, so absolute numbers are noisy; the 1-vs-2 comparison is
  consistent. Each replica is capped at 1 vCPU, so a second replica roughly doubles the CPU-bound
  (ffprobe + Tika) capacity.
- `ml` is not needed; `monitoring` is optional (live Grafana panels).
- **PID budget:** each replica has `pids_limit: 300`. On 2026-10-16 (50 threads, one replica,
  limits as above) 289 warm-up and 392 measured uploads returned 200 with no increase in the
  cgroup's `pids.events` `max` counter. Check it on the measurement host before and after a run:
  `docker compose exec -T file-service sh -c 'cat /sys/fs/cgroup/pids.max; cat /sys/fs/cgroup/pids.events'`.
  An increase means task creation was denied; raise both `pids_limit` and
  `deploy.resources.limits.pids` only in a benchmark override, never the defaults.
- Artifacts (`*.mp4`, `*.jtl`, `report-*/`) are git-ignored.
