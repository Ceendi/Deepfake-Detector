# Soak and multi-user test (2026-10-16)

One hour of mixed load from seven users against the full stack (`core` + `auth` + `ml`, dev realm) at
`main` `cc0cbfe`, preceded by a burst that checks the per-user limits. Goal: no memory creep, no restarts,
no dead letters, every analysis terminal with a domain outcome, cleanup running under load.

**Environment:** Windows 11, Docker Desktop (WSL2, 14 GB / 12 vCPU, Defender exclusions as in
[d4-replication.md](d4-replication.md)), CPU-only inference, one replica of every service, product
limits from `.env.example` (`BACKPRESSURE_MAX_INFLIGHT=20`, `BACKPRESSURE_MAX_INFLIGHT_PER_USER=3`,
`UPLOAD_MAX_CONCURRENT=4`, `UPLOAD_MAX_CONCURRENT_PER_USER=2`) except the gateway upload rate limit
(raised so it does not hide the file-service limits). Cleanup shortened so it runs within the hour:
`UPLOAD_UNUSED_RETENTION=10m`, `FILE_CLEANUP_SCAN_INTERVAL_MS=60000`, `FILE_CLEANUP_RETENTION_HOURS=0`,
`ARTIFACT_CLEANUP_ORPHAN_AGE=15m`.

**Users:** `deepfake-loadtest` plus six extra `client_credentials` clients with the `USER` role,
created in the throwaway Keycloak (dev realm clients cannot use the password grant).

**Media:** a public-domain 30 s / 60 s / 180 s address with a visible face (same clips as
[live-acceptance.md](live-acceptance.md#video-with-a-real-face-2026-10-15)), 55 s of speech (FLAC),
and `load-tests/jmeter/sample-5mb.mp4` (no face).

## Burst: per-user limits

| Check | Request | Result |
|---|---|---|
| Analysis backpressure | 7 users × 5 `POST /api/analysis` at once | 20 × `201`, 15 × `429` (`USER_LIMIT` / `GLOBAL_LIMIT` with `queuePosition`, `Retry-After: 5`); all 20 COMPLETED |
| Upload admission | 3 users × 4 uploads of 14 MB at once | 4 × `200`, 3 × `429 UPLOAD_USER_LIMIT`, 4 × `503 SERVICE_UNAVAILABLE`, **1 × `500`** |
| SSE admission | 6 viewers on one analysis | 4 streams to the terminal event, 2 × `429` (per-analysis cap 4) |

The `500` came from the gateway: `PrematureCloseException: Connection has been closed BEFORE response,
while sending request body`. The file-service upload filter rejects before reading the body, and Tomcat
drained only 2 MB (`max-swallow-size` default) before resetting the connection. Reproduced at 2 of 72
rejections (4 users × 4 parallel 14 MB uploads); with `max-swallow-size: 500MB`, 0 of 144. Fixed in #221.

## Soak: 60 minutes, 21 concurrent clients

Each user ran three loops. Each loop:
1. Uploads a file, weighted 40 % face 30 s FULL, 25 % speech AUDIO, 15 % no-face VIDEO, 10 % face 60 s
   FULL, and 10 % upload only, never analysed (left for the unused-upload cleanup).
2. Creates the analysis and retries on `429`.
3. Cancels 10 % of analyses 1–8 s after creation.
4. Follows 30 % over SSE and polls the rest.
5. Deletes half of the finished analyses: first the record, then the file.

| | Count |
|---|---:|
| Analyses finished | 354 (COMPLETED 275, FAILED 49, CANCELLED 30) |
| FAILED reason | 49 × `NO_FACE_DETECTED` (all no-face clips that were not cancelled) |
| Verdicts | 275 × REAL (all real recordings) |
| Cancel | 30 × `200`, 3 × `409` (already finished) |
| Backpressure `429` while creating | 246 (retried) |
| Records / files deleted | 175 / 175 (`204`) |
| Upload-only files | 36 |
| Analyses not terminal, timeouts, client errors | 0 |

21 clients against 20 global slots keep the system saturated: one video and one audio detector process
about 5.5 analyses per minute. End-to-end time per analysis (upload → terminal, including waiting for a
slot and the queue): p50 275–284 s, p95 312–322 s for every media type. The detectors' own time
is in [detector-scaling.md](detector-scaling.md). The maximum queue depth was 23 messages.

### Stability

| Check | Result |
|---|---|
| Restarts / OOM kills | 0 |
| `*.dlq` and `analysis.backend.quarantine` | 0 messages in all 60 samples |
| ERROR logs (gateway, orchestrator, file-service) | none except the burst `500` above |
| SeaweedFS `wrong jwt` / `timeout awaiting` | 0 |
| Outbox | 0 unsent rows at the end; sent rows are removed with their analysis (`ON DELETE CASCADE`) |
| Unused-upload cleanup | 0 unclaimed uploads older than 11 min at the end; 213 objects purged from S3 |

Memory (MiB, `docker stats`, sampled every minute):

| Container | t = 5 min | t = 30 min | max | end | limit |
|---|---:|---:|---:|---:|---:|
| video-detector | 2424 | 2436 | 2437 | 2436 | 3072 |
| audio-detector | 1914 | 1935 | 1979 | 1957 | 3072 |
| seaweedfs | 393 | 363 | 605 | 595 | 2048 |
| keycloak | 697 | 704 | 706 | 706 | 1024 |
| orchestrator | 589 | 608 | 616 | 613 | 1024 |
| file-service | 437 | 456 | 465 | 464 | 1024 |
| gateway | 323 | 340 | 350 | 349 | 512 |
| eureka-server | 262 | 271 | 274 | 274 | 512 |
| rabbitmq | 131 | 207 | 207 | 138 | 512 |
| postgres | 115 | 94 | 126 | 94 | 1024 |

The detectors stay flat after warm-up. The JVM services grow 15–25 MiB per hour. That is the heap
expanding, not live data: after the run the live set after GC (`jvm_gc_live_data_size_bytes`) was
72 MiB in the orchestrator and 46 MiB in file-service. SeaweedFS rises and falls with GC, staying well
under `GOMEMLIMIT` (1536 MiB).

## Conclusion

Pass. Over one hour under saturation:
- nothing restarted;
- nothing reached a dead-letter queue;
- memory stayed bounded;
- every analysis ended with a domain outcome;
- cancellations, deletions and the unused-upload cleanup worked under load.

The per-user and global limits return the documented `429`/`503`, except for the rare `500`, fixed in #221.
