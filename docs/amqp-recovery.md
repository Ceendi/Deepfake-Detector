# Backend AMQP recovery

Progress is advisory. Its dedicated AUTO listener makes three attempts, separated by
100ms and 200ms, then logs and acknowledges the event. It never publishes a terminal
failure. The database PROCESSING heartbeat commits before cache invalidation, Redis
snapshot or SSE delivery; rollback-only transactions and commit failures emit nothing.
Post-commit advisory failures are logged without changing the committed acknowledgement.

Results retain the configured Spring Boot finite retry policy (defaults: four attempts,
1s/4s/15s delays). Exhaustion republishes to `analysis.results.dlq`. Terminal DLQs use
AUTO acknowledgement, three database attempts with 250ms/500ms delays, and skip retries
for poison payloads. Exhausted database failures and poison payloads publish to durable
`analysis.backend.quarantine`, a backend-owned queue with no automatic consumer.

Task DLQs infer the source from the queue. Result DLQs require a terminal status and a
valid source; legacy progress messages left by the previous shared recoverer, including
`x-original-routingKey=analysis.progress`, are quarantined. Only an expected, unaccepted
source may fail an active analysis. Accepted FULL sources, cancelled/terminal analyses
and unexpected sources are ignored through a PostgreSQL conditional update.

Recovery publications are persistent, mandatory and use correlated publisher confirms.
Returns, nacks and missing confirms cannot acknowledge the original. The affected
listener force-stops and throws an immediate-requeue exception, explicitly overriding
`default-requeue-rejected=false`. AUTO mode applies that rejection; a closed channel
also requeues an unacknowledged original. The other listener remains independent.
Publication and acknowledgement remain at-least-once: a timeout/nack may have copied a
message before the publisher knew, so quarantine inspection/replay must tolerate duplicates.

A dedicated single-thread lifecycle scheduler resumes only after the shutdown completion
callback. Failure delays double from 5s to a 60s cap, with no total restart limit; ordinary
extended broker outages recover automatically. Shutdown failure retries shutdown before
resuming. Concurrent successful deliveries cannot erase pending shutdown/resume work.
The `amqpRecovery` health contributor and `/actuator/health/readiness` remain DOWN until
a resumed delivery or recovery publication succeeds; Rabbit connection health alone
cannot hide a stopped listener. No unauthenticated reset endpoint is introduced.

Container environment controls (also wired in Compose):

| Variable | Default | Purpose |
|---|---|---|
| `AMQP_CHANNEL_RPC_TIMEOUT` | `1s` | Bound channel RPCs, including cancellation/channel shutdown |
| `AMQP_RECOVERY_CONFIRM_TIMEOUT` | `2s` | Wait for the recovery publication's correlated confirm/return |
| `AMQP_RECOVERY_DELAY` | `5s` | Initial lifecycle recovery delay |
| `AMQP_MAX_RECOVERY_DELAY` | `60s` | Cap the increasing delay |

Shutdown uses force-stop and a 1s completion wait. Connection establishment retains its
3s budget. These are operation budgets, not one combined whole-message deadline;
ordinary message retries have the finite budgets described above. SSE delivery itself
remains synchronous in this change and is handled separately by PR15.

All existing shared queue durability, DLX arguments and detector payloads remain unchanged.
No detector source, worker threading or source upload cleanup changes are included.

Validation fixtures cover deferred PostgreSQL commit failure/rollback-only progress,
transient and exhausted terminal commit failures, legacy parked progress, accepted FULL
source duplicates, real Rabbit mandatory returns, overflow publisher nacks, paused-broker
TCP-alive timeouts, retained originals, bounded delivery counts, independent listeners and
automatic delayed recovery. Existing production Python worker acceptance also verifies the
shared declarations. When local Docker is unavailable, use the manual-only orchestrator
verification job in Infrastructure smoke against the independently reviewed frozen commit;
compile/unit results alone do not establish broker/database fault acceptance.
