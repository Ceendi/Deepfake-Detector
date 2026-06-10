# MO benchmarks — cache / index / connection pool

Measures the three backend optimizations (orchestrator). Report:
[`docs/optimization-reports/backend-cache-index-pool.md`](../../docs/optimization-reports/backend-cache-index-pool.md).

**Tools:** `psql` (in the postgres container), `wrk` (via `williamyeh/wrk`, run on the `deepfake`
network), and the `deepfake-loadtest` client for a token (added in the D4 PR; the orchestrator
validates its USER role). Bring up `docker compose --profile core --profile auth up -d` first.

## 1. Indexes — `EXPLAIN (ANALYZE, BUFFERS)`, no auth

```powershell
docker exec -i deepfake-detector-postgres-1 psql -U deepfake -d deepfake < seed-analyses.sql      # ~100k rows
docker exec -i deepfake-detector-postgres-1 psql -U deepfake -d deepfake < explain-history.sql     # with vs without index
```

The history query (`findByUserId`, ORDER BY created_at DESC LIMIT 20) should be an **Index Only Scan**
on `idx_analysis_user_created` with the index, and a **Seq Scan + Sort** after the index is dropped
(dropped inside a transaction that rolls back, so the schema is restored).

## 2. Cache — GET /api/analysis/{id} hit vs forced miss

`cache.enabled` toggles the cache-aside without flushing Redis.

```powershell
$env:LOADTEST_CLIENT_SECRET = "<same as .env>"
# hit (cache on, default):
docker compose --profile core --profile auth up -d orchestrator
.\bench-http.ps1 -Label cache-hit
# miss (cache off): rebuild not needed, just set env and recreate orchestrator
$env:CACHE_ENABLED = "false"; docker compose --profile core --profile auth up -d orchestrator
.\bench-http.ps1 -Label cache-miss
```

## 3. Connection pool — HikariCP 10 vs 20 under load

Run with the cache **off** (so every request hits Postgres and the pool matters):

```powershell
$env:CACHE_ENABLED = "false"
$env:DB_POOL_MAX = "10"; docker compose --profile core --profile auth up -d orchestrator; .\bench-http.ps1 -Label pool-10
$env:DB_POOL_MAX = "20"; docker compose --profile core --profile auth up -d orchestrator; .\bench-http.ps1 -Label pool-20
```

Pool saturation shows up in `hikaricp_connections_pending` / `hikaricp_connections_acquire_seconds`
(`docker exec deepfake-detector-orchestrator-1 wget -qO- http://localhost:8082/actuator/prometheus | findstr hikari`).

## Notes

- `wrk` runs on the `deepfake` network and targets `orchestrator:8082` directly — the orchestrator has
  no host port, and this isolates its cache/pool from the gateway and rate-limiter.
- The token subject owns the seeded target row, so the IDOR guard returns 200.
- A single-instance orchestrator with fast indexed reads may show only a small pool effect — report it
  honestly rather than inflating it.
