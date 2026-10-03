# Transactional analysis capacity

The orchestrator admits at most `backpressure.max-inflight` active analyses (default
20). One `FULL` analysis occupies one place, even though it publishes two tasks.
`PENDING` and `PROCESSING` rows in PostgreSQL are the capacity authority.

## Transaction boundary

`AnalysisService.create` validates identifier lengths before admission and runs at
`READ_COMMITTED` isolation. `BackpressureGuard.acquire` requires the same transaction
and uses its JDBC connection to take `pg_advisory_xact_lock(114545, 1)`. That lock is
held through the insert, publication, and database commit or rollback. After acquiring
the lock, pending inserts in the enclosing transaction are flushed and active rows
are counted. A waiting admission uses a fresh statement snapshot, so it sees the
preceding writer's commit. Stronger snapshot isolation is explicitly rejected.

There is no independently acquired Redis slot or fail-open reservation to compensate.
A create rollback leaves no active row and PostgreSQL releases the lock automatically,
including when a deferred constraint fails during commit. A terminal update frees
capacity through its committed database status; rollback leaves the active row counted.
Duplicate terminal messages cannot decrement a separate counter. Cache, cancellation,
progress, and result-deduplication Redis behavior otherwise stays unchanged.

This is a deliberately small admission gate for the current orchestrator. Concurrent
creates serialize until transaction completion; terminal updates do not take the gate
and their uncommitted changes remain counted. All admissions must use this gate and
the same PostgreSQL database. An unavailable database cannot safely admit an analysis.
The existing partial active-analysis index serves the count query. The inflight metric
uses that same count instead of the obsolete Redis key.

## Deployment and existing drift

Stop the old orchestrator before starting this version; do not overlap old and new
writers because the old version does not use the database admission lock. No database
migration, Redis reset, key deletion, or data replay is required. Existing active rows
are counted immediately, including analyses created while Redis was unavailable. If
there are already more than the configured limit, new creates return 429 until enough
analyses become terminal. The old `analyses:inflight` key is ignored and may remain
untouched, regardless of its value. Restart needs no separate correction process, so
there is no correction-versus-reservation race.

Do not roll back to the old limiter with its stale Redis counter while accepting work.
For an operational rollback, stop admission and finish or fail existing active work
before coordinating restoration of the previous limiter.

## Regression verification

`AnalysisCapacityTransactionIntegrationTest` uses real PostgreSQL and Redis containers
and disables the enclosing test transaction. It verifies publication exceptions,
deferred database errors at commit, validation, terminal rollback/commit, duplicate
terminal messages, existing active rows with stale Redis data, simultaneous admissions and terminal commit with guard recreation,
an admission demonstrably waiting on a PostgreSQL advisory lock, an uncommitted terminal
update, multiple creates in one transaction, and Redis unavailability. Broker exceptions
are injected through `RabbitTemplate`; they verify transaction cleanup, not delivery
reliability. The existing Redis outage and aggregation/cancellation races are also part
of the module verification.

Publication still happens before commit. Durable dispatch and redelivery are separate
PRs in the remediation plan; this change only makes capacity consistent with the database.

### Executed on 2026-10-03

- JDK 25, `./mvnw verify` in `orchestrator`: **177 tests, 0 failures, 0 errors,
  0 skipped**, including 12 capacity transaction integration cases and the existing
  PostgreSQL, Redis, and RabbitMQ Testcontainers tests. Packaging and JaCoCo report
  generation completed successfully.
- The publication-failure and deferred-commit-failure regressions were run against
  the unchanged production baseline `6947d83` in a temporary checkout. Both reproduced
  the leak: after 20 failed creates, the next valid create threw
  `TooManyAnalysesException`. Both pass with this change. The baseline copy only
  adapted the unused guard-recreation constructor to compile against the old API;
  the two exercised regression methods were unchanged.
- Independent read-only review found no remaining actionable issues after deployment
  and lifecycle comments were corrected. `git diff --check` passed.

Not executed: a full Compose end-to-end flow with detector inference; a complete
orchestrator process/container restart (guard recreation and existing committed rows
were tested); a real broker outage specifically during the new create-rollback test
(publication exceptions were injected, while existing RabbitMQ integration tests ran).
No frontend or detector code changed, so their builds and model checks were not run.
