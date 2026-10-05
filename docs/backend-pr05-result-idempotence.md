# Durable per-source result acceptance (PR05)

Baseline: `9cafd1ceddb1a802369a2f677c93a0056717fe55`, including merged PR01–04
(#85, #88, #89, #90). The local remediation plan's implementation register is stale.
This change covers PR05 only.

## Persistence and transaction boundary

An existing non-NULL `video_prob` or `audio_prob` is the durable acceptance marker
for a successful source. Each conditional update writes probability and JSONB details
atomically, only for an active analysis of the expected type whose source probability
is NULL. No migration or inbox table is required. Existing accepted probabilities,
including zero and results without metadata, are protected immediately on deployment.

PostgreSQL holds the row lock until the service transaction commits or rolls back.
After a competing transaction commits, a waiting UPDATE rechecks its predicate against
the committed row. Thus only one differing result from a source can be accepted.
Disjoint video/audio results serialize on that same row, survive together, and the
second accepted source completes FULL using both stored probabilities. The final
status update and source write share the transaction.

A source failure uses the same active/type/unaccepted-source predicates. The first
accepted failure still fails the whole analysis; the terminal status is its durable
marker. A failure delivered after that source's success cannot fail a partially
completed FULL analysis. Failure of the other, still unaccepted source can fail FULL,
preserving the existing policy. This PR does not add partial-failure aggregation.

Redis deduplication is an optional shortcut. Only a successful conditional acceptance
registers an after-commit marker. Missing/expired markers and Redis outages fall back
to PostgreSQL. Zero-row updates leave every column, including `updated_at`, unchanged
and do not mark a rejected source. A transaction rollback or deferred commit failure
rolls back both acceptance and final status, leaves no Redis marker and permits retry.

PR01 counts active PostgreSQL rows for capacity. There is no external release counter:
a terminal update frees one place only when committed. A duplicate terminal delivery
cannot free the place occupied by another analysis. SSE publication retains its
existing after-commit behavior; redesigning streams, recovery or dispatch is outside
PR05.

## Contract and deployment

Only exact `video` and `audio` source names are valid. VIDEO accepts video, AUDIO
accepts audio, and FULL accepts both. Only `COMPLETED` and `FAILED` are terminal result
statuses. Invalid source values or statuses are ignored without persistence or marking;
unexpected sources are rejected by the conditional SQL predicates.

The PR04 audio probability, metadata, threshold behavior and FULL weights (0.6 video,
0.4 audio) remain unchanged. Deploy the orchestrator normally without clearing Redis,
replaying finished analyses, or editing historical migrations. Previously overwritten
history cannot be reconstructed by this change. Rollback to the old orchestrator
removes the per-source guarantees.

## Verification

Run with JDK 25 and Docker available, from `orchestrator`:

```sh
./mvnw -B -ntp verify
./mvnw -B -ntp -Dtest=AnalysisResultAcceptanceIntegrationTest,AnalysisServiceAggregationRaceIntegrationTest,AnalysisServiceCancelRaceIntegrationTest,AudioScoreContractIntegrationTest test
```

`AnalysisResultAcceptanceIntegrationTest` uses disposable PostgreSQL 18.4 and Redis
8.8 containers, Flyway's real schema, the proxied service and real admission guard.
It has no enclosing test transaction. Independent JDBC snapshots check committed
probabilities, JSONB details, errors, verdict, confidence and timestamps. It covers:

- FULL video success, Redis marker loss, differing success and FAILED duplicates,
  then audio success with the original video details and weighted result.
- Deterministic same-source contention for both sources: the second transaction must
  appear in `pg_blocking_pids` while the first transaction is open. Commit keeps the
  first success; rollback lets the competing success or failure be accepted.
- A first FAILED delivery racing a success, with both commit and rollback, and
  legacy accepted zero probabilities with NULL details and no Redis marker.
- Invalid/missing/non-string sources, wrong sources for single-source analyses and
  non-terminal result statuses without changes or dedup markers.
- A deferred PostgreSQL constraint trigger causing actual commit failure for all
  analysis types, and explicit rollback of an accepted failure, followed by retry.
- Late success and failure deliveries for COMPLETED, FAILED and CANCELLED analyses,
  with byte-for-byte SQL snapshots and a new analysis occupying the freed place.

Existing PostgreSQL aggregation/cancel races and RabbitMQ audio fixtures exercise
cross-source aggregation, cancellation and the unchanged PR04 wire contract.
Testcontainers owns and removes the disposable resources; these commands do not use
Compose or the user's databases, volumes or Redis instance.

## Local validation record (2026-10-05)

- JDK 25.0.4.1, `./mvnw -B -ntp verify`: 258 tests, zero failures, errors or skips.
  This includes 40 acceptance cases, 32 PR04 RabbitMQ audio fixtures, and existing
  aggregation/cancellation races. The existing JSONB audio-write fixture now correctly
  creates an AUDIO analysis instead of relying on an audio write into VIDEO.
- The new FULL marker-loss regression failed against unchanged main sources because
  the committed probability and JSONB details were overwritten by the duplicate.
- A fresh GPT-6.1 Sol High reviewer checked the diff, transaction boundaries and tests,
  then verified the final test results after the fixture correction and added first-failure
  and legacy-zero coverage. No actionable findings remained.
- Full-stack/heavy-model inference smoke was not run locally for this orchestrator-only
  change. Worker recovery/outbox integration and accepted PCRE2/Grafana limitations
  remain outside PR05.
