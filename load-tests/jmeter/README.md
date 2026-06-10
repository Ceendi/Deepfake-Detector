# D4 load test — File Service upload (1 vs 2 replicas)

Proves D4: replicating `file-service` behind the Gateway's Eureka load balancer improves throughput / p95.
50 concurrent threads × 60s, ~5 MB multipart upload through the Gateway (`lb://file-service`).

**Tools:** Apache JMeter 5.6.x (on PATH), ffmpeg (sample), Docker. Report lands in
[`docs/optimization-reports/d4-replication.md`](../../docs/optimization-reports/d4-replication.md).

## 0. One-time prep

```powershell
# Valid ~5 MB MP4 (random blobs get a 422 from Tika+ffprobe and never reach S3).
.\generate-sample.ps1            # or ./generate-sample.sh

# .env must define LOADTEST_CLIENT_SECRET (see .env.example). Export it for the runner too:
$env:LOADTEST_CLIENT_SECRET = "<same as .env>"
```

## 1. Variant 1 — replicas=1

```powershell
# Rate-limit raised so the per-user bucket doesn't throttle 50 threads on one token.
$env:UPLOAD_RL_REPLENISH = "10000"; $env:UPLOAD_RL_BURST = "10000"
$env:FILE_SERVICE_REPLICAS = "1"
docker compose --profile core --profile auth up -d --build
```

Wait for warmup, then **verify** before measuring:
- `http://localhost:8761` → exactly **1** `FILE-SERVICE` instance.
- One manual upload returns **200** (not 422/401/429) — e.g. via the runner with `-Threads 1 -Duration 5`.

```powershell
.\run-loadtest.ps1 -Label r1
```

## 2. Variant 2 — replicas=2

```powershell
$env:FILE_SERVICE_REPLICAS = "2"
docker compose --profile core --profile auth up -d
```

**Wait ~45–60s** (Eureka registration + Gateway LB cache), then verify:
- `http://localhost:8761` → **2** `FILE-SERVICE` instances (distinct IPs).
- A few warmup uploads hit **both** replicas: `docker compose logs file-service | Select-String "/api/files/upload"` shows two container IDs.

```powershell
.\run-loadtest.ps1 -Label r2
```

## 3. Compare

Open `report-r1/index.html` and `report-r2/index.html` (throughput + response-time graphs), or read
`results-r1.jtl` / `results-r2.jtl`. Fill the table + charts in the report. Criterion: improvement in ≥1 metric.

## Knobs (all `-J` properties, defaults in brackets)

`host`[localhost] `port`[8080] `threads`[50] `duration`[60] `rampup`[5] `kcHost`[localhost]
`kcPort`[8180] `sampleFile`[sample-5mb.mp4] `loadtestSecret`(required).

## Notes / validity

- Keep **identical** conditions across both runs (same sample, same `-J` params, fresh `up`, same warmup) — the only variable is `FILE_SERVICE_REPLICAS`.
- Client (JMeter) and server share the host → absolute numbers are noisy, but the 1-vs-2 comparison is consistent.
- Each `file-service` replica is capped at 1 vCPU (compose `deploy.resources.limits`), so the 2nd replica genuinely ~doubles the CPU-bound (ffprobe+Tika) upload capacity.
- The `ml` profile is not needed (upload doesn't touch detectors); `monitoring` is optional (Grafana panels for live view).
- Artifacts (`*.mp4`, `*.jtl`, `report-*/`) are git-ignored.
