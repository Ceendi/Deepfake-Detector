# Durable analysis artifact cleanup

`DELETE /api/analysis/{id}/record` keeps its owner check, terminal-state restriction and 204 response.
The deletion transaction records every persisted Grad-CAM key in `artifact_cleanup` before removing
its analysis. Both changes commit or roll back together. Migration V5 deliberately gives cleanup
work no foreign key to the parent, so deletion cannot cascade away the work. No storage call runs
on the deletion request thread. PostgreSQL failures still fail the request; storage outages leave
committed, durable work.

Reference lookups use dedicated GIN indexes on the stored Grad-CAM arrays. Worker database
transactions have a five-second timeout. The worker claims one due key in a short transaction using `FOR UPDATE SKIP LOCKED`, then releases
the database connection before `DeleteObject`. A successful delete removes the queue record in a
separate short transaction. Failure records exponential backoff; work is retained without a retry
limit. Missing objects are removed idempotently. A crash before completion leaves a lease that
expires for the next process. Token checks prevent a stale worker from acknowledging another lease.
The lease must exceed the configured S3 whole-call budget plus one second. All calls use the existing
orchestrator client and its default 5s total / 2s attempt budget, including response consumption.

Cleanup runs on its own single-thread scheduler. The non-default scheduler bean is explicitly selected
by these tasks, leaving Boot's ordinary scheduler available to outbox publishing, recovery and SSE.
At most ten individual storage deletions run per batch by default, so an outage can occupy the cleanup
thread for up to roughly 50s plus database/scheduling overhead. It cannot occupy the ordinary scheduler
or hold a database connection while storage is blocked. Listing is a separate bounded task on that same
cleanup scheduler; large cleanup backlogs may delay scans, but each scan always advances one page.

## Conservative orphan discovery

The worker lists only `analysis-artifacts`; startup rejects any other bucket. It does not list or delete
`deepfake-uploads`, whose lifecycle remains with file-service. A potential orphan must be older than
24 hours by S3 `LastModified` and match one of the current worker attempt formats exactly:

- `{analysisUUID}/audio/gradcam_{32-lowercase-hex-attempt}.png`
- `{analysisUUID}/video/gradcam_frame_{at-least-two-digits}_{32-lowercase-hex-attempt}.png`

Fresh objects, unknown/legacy names and objects without a modification timestamp are skipped. Legacy
names explicitly recorded on a deleted analysis still receive durable deletion work. Active analyses
(`PENDING`/`PROCESSING`) protect their UUID prefix, and any currently recorded Grad-CAM reference in
any analysis protects the object. These checks run both when discovering candidates and immediately
before claiming a deletion. If an object becomes protected after discovery, its queued work is discarded;
a later scan may rediscover it after it is unreferenced. Terminal analyses cannot accept new source
results, and generated analysis UUIDs are not reused.

Each scan requests at most 100 objects and persists its continuation token in `artifact_cleanup_scan`.
Candidate inserts and cursor advancement commit together. The next process resumes the next page,
so a protected first page cannot starve older garbage on later pages. At the end of the listing the
cursor resets for a new pass. Listing failures preserve the cursor; a truncated page without an advancing
token is rejected instead of looping. A leased scan prevents overlapping processes from advancing the
same cursor. There is no bucket-wide purge, applied-migration edit or volume reset.

## Configuration

YAML, Compose and `.env.example` expose the same defaults:

| Variable | Default | Purpose |
| --- | --- | --- |
| `ARTIFACT_CLEANUP_ENABLED` | `true` | Run deletion and discovery tasks |
| `ARTIFACT_CLEANUP_POLL_INTERVAL_MS` | `1000` | Delay between deletion batches |
| `ARTIFACT_CLEANUP_SCAN_INTERVAL_MS` | `60000` | Delay between bounded listing pages |
| `ARTIFACT_CLEANUP_BATCH_SIZE` | `10` | Deletes/protected items examined per run, 1–100 |
| `ARTIFACT_CLEANUP_PAGE_SIZE` | `100` | Objects listed per page, 1–1000 |
| `ARTIFACT_CLEANUP_ORPHAN_AGE` | `24h` | Minimum age of an unreferenced attempt object |
| `ARTIFACT_CLEANUP_LEASE` | `30s` | Crash recovery lease, greater than S3 budget + 1s |
| `ARTIFACT_CLEANUP_RETRY` | `5s` | Initial retry delay |
| `ARTIFACT_CLEANUP_MAX_RETRY` | `1h` | Maximum exponential retry delay |

Disabling the worker stops remote cleanup and discovery, but deletion transactions still enqueue work.
On re-enabling it, durable work resumes. The backlog is observable in `artifact_cleanup` (attempt count,
next due time and lease), and the listing cursor in `artifact_cleanup_scan`; retry warnings identify keys.

## Verification

`ArtifactCleanupIntegrationTest` uses real migrated PostgreSQL, the actual Spring deletion proxy and the
production S3 client against an HTTP fault fixture. It covers deletion during storage outage, restart and
restoration, rollback, duplicate/missing keys, partial success, expired leases and stale acknowledgments,
active/referenced protection, durable cursor/batch rollback, and a blocked delete with zero checked-out
database connections. `ArtifactCleanupSchedulingTest` blocks cleanup while the ordinary scheduler continues.
`ArtifactCleanupStorageBudgetTest` warms the production client, stalls live TCP delete/list requests
under the default budgets, then verifies recovery with that same client without needing Docker.

`ArtifactCleanupSeaweedIntegrationTest` uses the actual Compose-pinned `chrislusf/seaweedfs:4.48` with
isolated data, signed bucket-scoped credentials and real timestamps. With a two-object page it resumes
through protected early pages to remove old later garbage, preserving active/referenced/fresh/unknown
objects and source uploads. It also checks repeated/missing deletes and denied source-bucket access.
Its conservative age is shortened to 30s for the fixture; production retains 24h.

Run `./mvnw -B verify` in `orchestrator/` with Java 25, Docker and the existing lightweight worker Python
runtime in `DETECTOR_TEST_PYTHON`. The manually dispatched **Infrastructure smoke** workflow includes a
manual-only **orchestrator-verify** job with Java 25/Python 3.12 and disposable fault containers. This is the
isolated verification fallback when local Docker is unavailable. It uses only `contents: read`, no deployment
or registry credentials. Dispatch against the frozen branch and inspect that job's full Maven result before
claiming acceptance; compiling tests or running unit tests alone does not establish Docker fault acceptance.

This PR is stacked on `fix/artifact-download-transactions`, which supplies storage budgets; merge the
Redis-budget PR, then the artifact-download PR, then this cleanup PR.
