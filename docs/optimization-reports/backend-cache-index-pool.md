# MO — backend optimizations: cache, index, connection pool

Three orchestrator optimizations, measured before/after on real runs. Harness:
[`load-tests/bench/`](../../load-tests/bench/).

**Setup:** AMD Ryzen 5 5600 (6C/12T), 16 GB; Postgres 18, Redis 8, orchestrator on Java 25. HTTP
load = `wrk` (4 threads, 50 connections, 30 s) on the docker network against `orchestrator:8082`
(direct, bypassing the gateway/rate-limiter). Index measured with `EXPLAIN (ANALYZE, BUFFERS)` over
100k seeded rows. The HTTP micro-benchmarks are single 30 s runs on a shared dev host, so absolute
numbers carry ~±25 % run-to-run noise — the **direction** is consistent and is the point.

## 1. PostgreSQL index (history query) — deterministic, dramatic

Query: `WHERE user_id = ? ORDER BY created_at DESC LIMIT 20` (the per-user history list), 100k rows.

| | with `idx_analysis_user_created` | index dropped |
|---|---|---|
| Plan | **Index Only Scan** | Parallel Seq Scan + top-N heapsort |
| Execution time | **0.164 ms** | 35.083 ms |
| Shared buffers | 5 | 1 686 |

~**214× faster**, ~337× fewer buffer hits. The covering index (`INCLUDE` = the `AnalysisSummary`
projection) makes it index-only (`Heap Fetches: 20`), so the list never touches the heap.

## 2. Redis cache-aside — `GET /api/analysis/{id}`

`cache.enabled` toggled the cache-aside (no Redis flush needed).

| | cache hit | cache miss (DB) |
|---|---|---|
| Throughput | **2 777 req/s** | 1 977 req/s |
| p50 | **14.9 ms** | 21.2 ms |
| p90 | 42.9 ms | 59.9 ms |

+40 % throughput, −30 % p50. The effect is real but moderate: the miss path is a single
primary-key `findById` (sub-millisecond in Postgres), so the cache mainly saves the query + JPA
mapping, not a heavy read. Both well under the targets (<50 ms hit / <200 ms miss). (p99 is dominated
by JVM/GC tail noise on the shared host and isn't a reliable cache signal here.)

## 3. HikariCP pool size — 10 vs 20 under load

Cache **off** (every request hits Postgres), 50 concurrent connections.

| | pool = 10 | pool = 20 |
|---|---|---|
| Throughput | 1 635 req/s | **2 506 req/s** |
| p50 | 26.4 ms | **15.9 ms** |
| Avg connection-acquire wait | 6.43 ms | **3.66 ms** |

The direct signal is the **acquire wait** (`hikaricp_connections_acquire_seconds`): with 50 clients
contending for 10 connections, threads queue 6.4 ms on average for a connection; doubling the pool
roughly halves that (3.7 ms) and lifts throughput +53 %. `connection-timeout=5s` keeps a saturated
pool failing fast rather than blocking the request thread indefinitely.

## Conclusion

- **Index** is the decisive win and the most robust measurement (deterministic): two orders of
  magnitude on the history query — keep the covering + partial indexes.
- **Cache** and **pool** each help under concurrency (cache: faster + offloads the DB; pool=20: less
  connection contention). Their absolute HTTP numbers are noisy on a shared dev box, but every run
  pointed the same way.
- Honest note: on this workload the DB is rarely the bottleneck for a single PK read, so cache/pool
  gains are moderate (tens of %) — not the index's 200×. The tuning is still worth keeping for the
  concurrency headroom it buys.

## Reproduce

See [`load-tests/bench/README.md`](../../load-tests/bench/README.md): seed + `EXPLAIN ANALYZE` for the
index; toggle `CACHE_ENABLED` / `DB_POOL_MAX` and re-run `bench-http.ps1` for cache/pool.
