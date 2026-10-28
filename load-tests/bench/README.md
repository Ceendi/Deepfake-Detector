# MO benchmarks — cache / index / connection pool

Measures the three backend optimizations (orchestrator). Report:
[`docs/reports/backend-cache-index-pool.md`](../../docs/reports/backend-cache-index-pool.md).

**Tools:** `psql` (in the postgres container), `wrk` (via the immutable image
`williamyeh/wrk@sha256:78adc0d9d51a99e6759e702a08d03eaece81c890ffcc9790ef9e5b199d54f091`, run on the `deepfake`
network), and the `deepfake-loadtest` client for a token (the orchestrator validates its USER
role). Set `KEYCLOAK_REALM_CONFIG=dev/realm-export.json` in the local
`.env` to enable this client, then bring up `docker compose --profile core --profile auth up -d`.
See [Keycloak configuration](../../infra/keycloak/README.md).

## 1. Indexes — `EXPLAIN (ANALYZE, BUFFERS)`, no auth

```powershell
Get-Content seed-analyses.sql | docker compose exec -T postgres psql -U deepfake -d deepfake    # ~100k rows
Get-Content explain-history.sql | docker compose exec -T postgres psql -U deepfake -d deepfake   # with vs without index
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
# miss (cache off): no rebuild, the changed env recreates the orchestrator
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
(`docker compose exec orchestrator wget -qO- http://localhost:8082/actuator/prometheus | findstr hikari`).

## Notes

- `wrk` runs on the `deepfake` network and targets `orchestrator:8082` directly — the orchestrator has
  no host port, and this isolates its cache/pool from the gateway and rate-limiter.
- The token subject owns the seeded target row, so the IDOR guard returns 200.
- A single-instance orchestrator with fast indexed reads may show only a small pool effect — report it
  honestly rather than inflating it.
