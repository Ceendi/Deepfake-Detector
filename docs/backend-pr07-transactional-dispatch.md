# Transactional analysis dispatch

PR07 requires PR05 durable per-source result acceptance and PR06 redelivery-safe workers.
PR06 was merged as #94 (`9295f2b0fdb2df9a82a62592066c61512b9ec76f`, main
`3a2c125`). Deployment order is **PR05 → PR06 → activate PR07**. Never activate
outbox replay with the earlier workers that ACK an existing processing key.

## Commit and delivery contract

File-service authorization and canonical key resolution finish before the writer's
transaction. The writer admits capacity using PostgreSQL, inserts the analysis, and
inserts one `analysis_task_outbox` row per expected source in the same transaction.
AUDIO/VIDEO have one row; FULL has independently tracked audio and video rows. The
outbox stores the canonical task payload, including audio mode, correlation ID,
original timestamp and a generated `task_id`. It never stores a JWT. A rollback,
including a deferred commit failure, leaves neither analysis nor tasks. Creation
returns after commit without contacting RabbitMQ; a broker outage does not reject a
successfully admitted analysis.

The scheduled publisher reads committed rows. It locks the analysis before its task
row, rechecks active status, deadline, unsent state and retry time, then sends a
persistent JSON message with mandatory routing and correlated publisher confirms.
The AMQP message ID and JSON `task_id` remain unchanged on retry. Only a positive
confirm **without a mandatory return** permits `sent_at` to commit. Nack, return,
connection failure or confirm timeout leaves the row unsent. The correlation token
is unique per attempt; it is not the durable task identity. The confirmed dispatch
updates the analysis's progress clock; FULL's missing source still has its own
deadline, so progress from its sibling cannot postpone dispatch forever.

Delivery is at least once. A process crash after confirm but before the database
commit (or an ambiguous timeout/commit failure) may deliver the same task again.
Both PR06 workers may recompute it and ACK only after confirming their result.
PR05 accepts the first result for each expected source in PostgreSQL; later results
cannot overwrite source details, change the FULL aggregate, resurrect terminal
state, or release capacity twice. Redis dedup keys are only hints.

## Retry, cancellation and finite waiting

Defaults: poll every 1 second, at most 20 tasks per batch, confirm wait at most
5 seconds, TCP connection timeout 3 seconds. Ordinary failed attempts persist
backoff of 5, 10, 20, 40, then 60 seconds. Each task transaction commits independently,
so one failed source does not roll back a confirmed sibling. A killed transaction
also rolls back its retry metadata: restart may retry immediately once; subsequent
attempts follow the polling cadence and persisted backoff. `RELIABILITY_OUTBOX_*`
settings in `application.yaml` control these limits.

The dispatch deadline is 120 seconds from analysis creation. The publisher stops
selecting overdue tasks. Recovery considers any unsent expected source overdue at
that deadline, regardless of sibling progress, and fails the active analysis on
its next scan. Once all tasks are confirmed, the existing 600-second idle-progress
threshold applies. Legacy analyses without outbox rows retain that idle policy.
The default recovery scan interval is 300 seconds. These are eligibility deadlines,
not real-time SLAs: scan scheduling, the current finite publisher batch, connection
setup, and row-lock waits add latency. Database outages or a stopped orchestrator
also delay terminal decisions until service recovers. No unfinished task is exempt
from recovery indefinitely on a live, healthy database. Configure the scan interval
lower when a shorter terminal-notification delay is required.

Publisher, cancellation, result acceptance and recovery serialize on the analysis
row. A terminal transition that commits first suppresses publication. If publication
wins first, cancellation waits for the confirm transaction, commits terminal state,
and sets the existing cooperative Redis flag after commit. Already queued work can
still run when Redis is unavailable, but its results cannot revive the analysis.
Recovery's candidate scan is advisory: the final transaction locks the analysis and
rechecks current status, timestamps and missing-source state in a fresh statement.
It cannot fail a candidate merely because an earlier scan preceded new dispatch or
progress. Expired rows are excluded from publisher batches so they cannot starve
new tasks while awaiting the recovery scan.

## Deployment, retained backlog and rollback

Apply the new Flyway V4 migration after the current V1–V3 history; do not modify
historical migrations. Stop the old orchestrator before starting the new version
(the supported deployment has one orchestrator). Existing PENDING/PROCESSING rows
have no outbox entries. Do **not** synthesize replay tasks for them: the historic
schema did not persist audio mode and a task may already be queued. Existing workers
can finish them; otherwise the existing progress deadline fails them and PostgreSQL
capacity becomes reusable. New analyses always have their expected outbox rows.

Unsent rows survive process/broker restarts with the same task identity and retry
state. Overdue or terminal analyses retain unsent rows for diagnosis, but those rows
are never selected for replay. Sent rows remain as dispatch history for the lifetime
of their analysis. Deleting terminal history cascades to its outbox rows. No queue
purge, Redis cleanup, or user-data deletion is part of normal rollout/recovery.

For publisher rollback, first disable it with `RELIABILITY_OUTBOX_ENABLED=false`
in the stack's `.env`, recreate only the orchestrator with the existing explicit
project name (`docker compose -p <project> --profile core up -d --no-deps orchestrator`),
and then stop the new orchestrator before replacing its binary. Compose forwards
all six outbox settings; changing `.env` alone does not change an already-running
container. Preserve V4 and all
outbox rows; older binaries use `ddl-auto: none` and can coexist with the additive
table. The older creation path will again publish directly and will not drain the
outbox backlog. Keep PR05/PR06 installed. Pending new-version analyses may finish
from previously confirmed messages or fail under the older recovery policy. When
PR07 is restored, only still-active, unexpired outbox rows retry; terminal analyses
are never replayed. Confirmed-but-unmarked messages may duplicate across rollback,
which is why PR05/PR06 must remain installed. Disabling the publisher without rolling
back creation deliberately queues new work only until its finite dispatch deadline.

## Verification

`./mvnw verify` on JDK 25 runs `TransactionalDispatchIntegrationTest` and the existing
`WorkerRedeliveryIntegrationTest` in the orchestrator CI job. PostgreSQL, RabbitMQ
and Redis are disposable Testcontainers instances; no application Compose project
or user volumes are touched. CI already installs the lightweight Python process
fixtures. Tests assert committed analysis/source data and admission capacity, not
just queue counts. They cover rollback, immediate production-worker results, broker
stop/restart, real mandatory returns and overflow nacks, partial FULL, terminal
suppression, cancel/publisher/recovery locking, deadline rechecks, legacy rows, and
SIGKILL/restart of the actual orchestrator after confirm before the sent marker.
The process probe adds only a barrier after the real RabbitTemplate confirm; it does
not replace dispatch, confirms, database writes, or worker consumers. Controlled
inference and filesystem S3 fixtures do not verify loading real ML checkpoints.
