# Backend reliability

How the backend behaves under duplicates, crashes and dependency outages (requirement D6).
Message and API shapes live in [contracts](contracts/); this page describes mechanisms.

Ground rules:

- **PostgreSQL is the authority** for admission, source acceptance and terminal state.
- **RabbitMQ delivery is at least once** everywhere. Every consumer tolerates duplicates.
- **Redis only carries hints** (cache, dedup, cancel flag). Every Redis call
  in the orchestrator and detectors fails open; correctness never depends on these hints.
  Gateway upload quotas deliberately fail closed (`503`); other gateway routes fail open.
- **Side effects run after commit** (cache eviction, SSE, Redis markers), so a rollback leaves none.

## Container recovery and broker storage

Compose runs Eureka, gateway, file-service and the single orchestrator instance with
`restart: unless-stopped`: a process crash restarts its container, while an explicit stop stays
stopped. An unhealthy healthcheck alone does not restart a container. Java containers use bounded
`json-file` logs (three 10 MiB files) and memory limits: 512 MiB each for Eureka/gateway, 1 GiB
each for orchestrator/file-service (per replica). With two file-service replicas, the running
`core` + `auth` + `ml` services have 15.375 GiB of configured memory limits (14.375 GiB with
one replica; SeaweedFS alone has 2 GiB, the frontend's nginx 128 MiB). Monitoring adds 2 GiB:
512 MiB each for Prometheus, Loki and Tempo, and 256 MiB each for Grafana and Alloy. The combined
limit is 17.375 GiB (16.375 GiB with one file-service replica), excluding one-shot init containers. These are ceilings, not
reservations: with one replica and both detectors analysing, the stack used about 6.6 GiB.
Allow additional room for Docker and the host, and use fewer profiles on a 16 GiB machine.

Eureka, gateway, file-service and monitoring drop all capabilities, prevent privilege escalation
and cap processes at 300. Eureka/gateway and monitoring use read-only root filesystems with a
64 MiB `/tmp` tmpfs; writable monitoring state lives in named volumes. The root-run monitoring
services retain only `DAC_OVERRIDE` to write existing volumes owned by root or their image user.
Grafana uses its image user and a new `grafana-data` volume, preserving its SQLite state across
recreation; settings in an old container's writable layer are not migrated automatically.
File-service keeps its writable root filesystem because multipart spooling must use disk:
charging two copies of a 500 MiB upload to tmpfs would exhaust its 1 GiB memory budget.

Java images set `-XX:MaxRAMPercentage=75` alongside compact object headers. The maximum heap is
approximately 384 MiB for Eureka/gateway and 768 MiB for orchestrator/file-service, rather than
[the JVM's default 25%](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html)
of the container limit. The remaining quarter of the container budget is
for native JVM memory and subprocesses such as ffprobe; total container limits stay the same.
The core integration test reads effective JVM flags and heap sizes inside every Java container.

The orchestrator container probes `/actuator/health/liveness`. The `amqpRecovery` contributor
remains in readiness, which can stay DOWN after a broker outage until a successful delivery or
recovery publication. That readiness state must not make a live process an unhealthy container.
Gateway probes liveness on its internal management port (`9090/actuator/health/liveness`),
so a Redis outage leaves the live container healthy. The full `/actuator/health` remains
available for dependency diagnostics. RabbitMQ checks both
`check_running` and `check_port_connectivity`, rather than only Erlang-node reachability.

RabbitMQ's effective memory budget is explicitly `512MB` (512,000,000 bytes) and the quorum WAL is capped at
64 MiB (`raft.wal_max_size_bytes=67108864`), leaving room for the Erlang runtime. Queue arguments,
delivery limits and application retry policy stay the same. Redis caps dataset memory at 200 MiB
inside its 256 MiB container and uses `volatile-lru`: only expiring hints/buckets can be evicted;
non-expiring keys are kept. The existing Redis outage behavior still applies to command errors.

RabbitMQ uses a stable hostname (`RABBITMQ_HOSTNAME`, default `rabbitmq`) so the node name and
its storage directory remain the same when Compose recreates the container with `rabbitmqdata`.
Queue durability and persistent publications then survive recreation, not just a process restart.

**Existing volumes:** before the first recreation with this configuration, read the running
container's hostname (`docker compose exec -T rabbitmq hostname`) and pin that exact value as
`RABBITMQ_HOSTNAME` in the deployment environment. Keep it pinned on later deployments. Changing
the node name on an existing volume starts a different node directory and hides its old queues
and messages; a definitions export alone does not preserve messages. If the old container is
already gone, identify the original node name in the volume or restore a backup under its original
node name before starting RabbitMQ. See [RabbitMQ backup and restore](https://www.rabbitmq.com/docs/backup).

Tests: `infra/tests/test_core_reliability.py` boots `core` on fresh isolated volumes, kills Java
processes, recreates RabbitMQ with a persistent message, verifies liveness during failed AMQP
recovery, and checks real Prometheus scrapes during file-service scaling.
It also reads the live broker/Redis memory settings. `infra/tests/test_monitoring_configuration.py`
runs native Prometheus/Loki/Alloy validators against valid and broken configs, checks dashboard JSON,
boots monitoring on fresh volumes and verifies that Alloy excludes another project's logs.

Alloy filters Docker discovery by `com.docker.compose.project`, using the resolved Compose project
name (`ALLOY_COMPOSE_PROJECT`, set by Compose, including `--project-name` overrides). Prometheus's
HTTP lifecycle/reload endpoint is disabled; edit configuration and recreate the container to apply it.

## Broker users and internal storage APIs

Applications never use the RabbitMQ administrator (`RABBITMQ_USER`), which is kept for manual
operations (management UI, `rabbitmqctl`). After the broker is healthy, the one-shot
`rabbitmq-init` service creates two untagged users through the management API, and the
orchestrator and detectors start only after it succeeds:

- `orchestrator` — declares the whole topology, consumes `analysis.results`, `analysis.progress`
  and the three `*.dlq` queues, publishes only `analysis.video`/`analysis.audio` on
  `analysis.exchange` and republishes to `analysis.dlx`.
- `detector` (shared by both detectors) — declares its own queues, consumes only
  `analysis.video`/`analysis.audio` and publishes only `analysis.results`/`analysis.progress`.

Topic permissions on `analysis.exchange` restrict publish routing keys, so a compromised detector
cannot publish tasks, consume results or progress, or publish to or read the backend DLQs and
quarantine. Neither user has access to the default exchange. Residual risk: both users need
`configure` and binding rights on what they declare, so a compromised detector can still delete or
rebind the shared exchange and queues it declares (for example bind `analysis.video.dlq` to task
routing keys, which fails those tasks). That disrupts processing but does not expose data.

The init declares both exchanges and the two quorum task queues with the application's arguments.
RabbitMQ requires an existing exchange for topic permissions, and creating a new queue with
`x-dead-letter-exchange` also needs read on the queue and write on the DLX, which neither user
gets. If a task queue is deleted, re-run `rabbitmq-init` before the services can recreate it.
Exact patterns are in `infra/rabbitmq/provision_users.py` and the
[AMQP contract](contracts/amqp-messages.md#broker-users).

Every run resets the users' passwords, tags and permissions. **Existing volumes:** add
`RABBITMQ_ORCHESTRATOR_PASSWORD` and `RABBITMQ_DETECTOR_PASSWORD` to `.env` and run
`docker compose` with every profile you use (`--profile core --profile ml up -d`); the init
provisions the users on the existing broker and Compose recreates the clients with the new
credentials. Queues, messages and the administrator are kept. Change a password the same way and
include `--profile ml`: detectors left running with the old password keep their connection but
fail on the next reconnect. Empty
passwords, control characters, an administrator named like an application user, or a non-admin
`RABBITMQ_USER` make the init fail, and the dependent services do not start.

SeaweedFS runs master, volume, filer and S3 in one process. `seaweedfs-init` also writes
`security.toml` with fresh random keys for `jwt.signing`, `jwt.signing.read`, `jwt.filer_signing`
and `jwt.filer_signing.read` on every run, and every component in that process loads the same
file at start. Tokens expire after 10 s, except volume write tokens (`jwt.signing`), which last
60 s. When a chunk upload times out after 30 s, the S3 gateway deletes the partial chunk with
that same token. A shorter lifetime rejects the delete with 401, and the orphaned chunk is
never reclaimed. Without it, the filer HTTP API and the S3 IAM gRPC API (`PutIdentity`) on the
network accepted anonymous calls that bypass S3 identities. The master gives volume JWTs to any
caller, so master, volume and filer also listen only on the container's loopback interface. Only
the S3 API (8333) and its JWT-protected gRPC port (18333) are reachable from other containers.
The unused Iceberg and Lance endpoints are disabled. Existing data stays readable after the
upgrade: chunk references use volume IDs, not addresses.

| Variable | Default | Meaning |
|---|---|---|
| `RABBITMQ_ORCHESTRATOR_PASSWORD` | none (required) | Password of the `orchestrator` broker user |
| `RABBITMQ_DETECTOR_PASSWORD` | none (required) | Password of the `detector` broker user |

Tests: `infra/tests/test_rabbitmq_users.py` provisions a pre-existing broker, checks tags, the
permission patterns, rotation and failed configurations. `tests/worker_redelivery` runs the real
detector runtime as `detector`, and its `test_broker_permissions.py` checks that both users are
refused everything outside their own queues and routing keys. `infra/tests/test_s3_configuration.py`
checks that internal SeaweedFS ports are unreachable from the network, that anonymous IAM gRPC
calls fail, and that the filer requires a JWT even on loopback.

## HTTP errors and request correlation

Orchestrator and file-service explicitly map request binding/negotiation failures to
400/405/406/413/415 instead of the unexpected-error handler. Disconnects do not trigger
another write; async timeouts have no body. Both avoid ERROR logging for those async
conditions. Unexpected application failures still return a generic 500 and log details.
Shared error messages are neutral Polish text; technical status-exception reasons
are not exposed. Clients accepting only SSE/PDF/binary data receive error status and
headers without JSON negotiation or warning logs.

The gateway validates `X-Correlation-ID` against `^[A-Za-z0-9-]{1,64}$`, replacing invalid
or absent values with a UUID and echoing the downstream ID. Both servlet services apply
the same rule to direct requests. The file claim call forwards the request's MDC correlation ID
and injects the active OpenTelemetry W3C context (`traceparent`, and `tracestate` when present).
File-service's Spring HTTP observation extracts this context; its correlation filter reads
`X-Correlation-ID`. The existing complete-response deadline, no redirects and no retries remain.

Security's CORS filter answers permitted preflights before JWT authentication. Origin
configuration has one source in the gateway, so routing cannot duplicate CORS headers.

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_CORS_ORIGINS` | `http://localhost:5173,http://localhost:4173` | Comma-separated frontend origins |

History always uses `createdAt DESC, id DESC` with a page size capped at 100, ignoring
client sort parameters. Regression tests: both `HttpExceptionContractTest` and correlation
filter tests, `SecurityConfigIntegrationTest`, `AnalysisServiceListTest`,
`AnalysisErrorContractTest`, `AnalysisFileResolutionIntegrationTest`.

## Operator defaults and monitoring

`.env.example` keeps tracing export off (enable `OTEL_TRACING_EXPORT_ENABLED` only with Tempo
running) and runs one file-service replica; Windows/WSL2 memory is covered in
[SETUP.md](../SETUP.md#windows--wsl2-memory). Histogram ranges cover 2 h for analyses and 10 min
for HTTP; they are metric bounds, not execution deadlines. Prometheus evaluates DLQ, stuck-recovery,
backlog and availability alerts ([observability](observability.md#alerts)); there is no outbound
alert transport. Database and RabbitMQ administrator credentials apply only to empty volumes;
application broker passwords are reconciled as described [above](#broker-users-and-internal-storage-apis).

## Creating an analysis

`POST /api/analysis` goes through three steps.

**1. Input resolution** (`AnalysisCreationService`, `FileMetadataClient`). Runs outside any
transaction (`Propagation.NEVER`). The orchestrator calls file-service
`POST /api/files/{id}/claim` with the caller's verified JWT and uses only the returned
`objectKey` as the analysis `fileKey` and the task `file_key`; a `fileKey` sent by the client is
ignored. Foreign, missing or soft-deleted files → `404`. Timeout, connection failure, any other
status or invalid metadata → `503`. Nothing is persisted on failure. The lookup has one deadline
covering connect, headers and body; no redirects or retries.

A file deleted after acceptance does not cancel the analysis. File-service keeps soft-deleted
objects for `FILE_CLEANUP_RETENTION_HOURS` (72 h); a task delayed past that fails normally.

**2. Capacity** (`BackpressureGuard`). At most `backpressure.max-inflight` active analyses globally
and `backpressure.max-inflight-per-user` per authenticated user (defaults 20 and 3); one FULL
analysis takes one place in both limits. Both counts include `PENDING`/`PROCESSING` rows, so there
is no separate counter to leak. Admission runs in the create transaction at `READ_COMMITTED` and
takes `pg_advisory_xact_lock(114545, 1)`, held until commit or rollback, so concurrent creates
serialize and each sees the previous commit. A rolled-back create occupies nothing; a terminal
update frees its place only when it commits. Both limits are checked under the same lock; reaching
either → `429` with `Retry-After`, the matching `retryAfterSeconds`, a `reason`
(`USER_LIMIT` or `GLOBAL_LIMIT`) and the configured `limit` that rejected admission.
The user limit takes precedence when both limits are reached. The frontend explains the
per-user limit separately; older bodies without `reason` retain the global queue message.
`queuePosition` remains the global active count plus the rejected request. The per-user query uses the partial `idx_analysis_active_user` index
on `user_id` for active rows, avoiding scans of terminal history. The `analyses_inflight` gauge
continues to report the global count.

**3. Transactional dispatch** (`AnalysisService.createResolved`, `TaskOutboxPublisher`). The same
transaction inserts the analysis and one `analysis_task_outbox` row per expected source (FULL has
two). The row holds the full task payload and a `task_id`; never a JWT. Creation returns after
commit without touching RabbitMQ, so a broker outage does not reject admitted work.

Outbox dispatch and stuck-job recovery share Boot's scheduler pool, with two threads by default
(`SCHEDULER_POOL_SIZE`), so a blocked recovery scan does not delay dispatch. Artifact cleanup
keeps its dedicated scheduler.

The scheduled publisher reads committed, unsent, due rows. Per task it locks the analysis row
(`FOR UPDATE SKIP LOCKED`), rechecks active status and the dispatch deadline, then publishes a
persistent, mandatory message (`message_id` = `task_id`) and waits for a correlated confirm.
`sent_at` commits only after a positive confirm without a return. Nack, return, timeout or
connection failure leaves the row unsent with backoff 5, 10, 20, 40, then 60 s. Each task commits
independently. A crash between confirm and commit can send the task twice; result acceptance
makes that safe. A confirmed dispatch bumps the analysis `updated_at`.

| Variable | Default | Meaning |
|---|---|---|
| `FILE_SERVICE_BASE_URL` | `http://file-service:8081` | File claim target |
| `FILE_SERVICE_LOOKUP_TIMEOUT` | `2s` | Whole lookup deadline |
| `BACKPRESSURE_MAX_INFLIGHT` | `20` | Active analyses limit |
| `BACKPRESSURE_MAX_INFLIGHT_PER_USER` | `3` | Active analyses limit per authenticated user |
| `BACKPRESSURE_RETRY_AFTER_SECONDS` | `5` | `Retry-After` on `429` |
| `SCHEDULER_POOL_SIZE` | `2` | Orchestrator scheduler threads shared by outbox and recovery |
| `RELIABILITY_OUTBOX_ENABLED` | `true` | Run the publisher |
| `RELIABILITY_OUTBOX_POLL_INTERVAL_MS` | `1000` | Publisher poll delay |
| `RELIABILITY_OUTBOX_BATCH_SIZE` | `20` | Tasks per poll |
| `RELIABILITY_OUTBOX_CONFIRM_TIMEOUT_MS` | `5000` | Confirm wait per publish |
| `RELIABILITY_OUTBOX_RETRY_SECONDS` | `5` | Initial backoff (doubles, capped at 60 s) |
| `RELIABILITY_OUTBOX_DISPATCH_TIMEOUT_SECONDS` | `120` | Dispatch deadline from creation |

Tests: `AnalysisFileResolutionIntegrationTest`, `AnalysisCapacityTransactionIntegrationTest`,
`TransactionalDispatchIntegrationTest`, `OutboxRecoverySchedulingTest`,
`infra/tests/test_analysis_file_ownership.py`.

## Detector workers

Code: `detector-runtime/detector_runtime/consumer.py`; detector-specific model, artifact,
metric and tracing hooks live in `audio-detector/src/consumer.py` and
`video-detector/src/consumer.py`. The runtime owns delivery, publication, topology,
cancellation, temporary directories and consumer health state. Both detectors share the same
connection loop and a single inference thread per process.

- **Delivery:** manual ACK, prefetch 1, AMQP heartbeat 30 s. Every valid, non-cancelled delivery
  is computed, including redeliveries and concurrent copies; there are no Redis processing claims.
  Task queues are quorum with `x-delivery-limit=5`. As documented in
  [RabbitMQ poison message handling](https://www.rabbitmq.com/docs/quorum-queues#poison-message-handling),
  on RabbitMQ 4.3 process crashes and
  lost connections with unacked work increment `x-delivery-count`; `basic.nack` returns do not.
  Count 0 is the initial attempt, counts 1–5 are retries. Exceeding the limit routes the task
  through the existing DLX to its source DLQ, where the source guard transitions it to FAILED.
  A crash loop therefore frees the queue, even when every attempt sends a fresh start ping.
- **Thread ownership:** one worker thread handles the attempt, including input download, model
  inference and artifact upload. The connection thread services pika I/O at one-second intervals
  and refreshes the consumer health heartbeat even while these operations block. Progress,
  results and ACK/NACK calls run on the connection thread through `add_callback_threadsafe`;
  the worker waits for each call and receives its exception. The task's trace and log context
  follow these callbacks. A lost connection releases pending calls and rejects all later calls
  from the abandoned attempt. Its cleanup completes before reconnecting and reusing the model.
- **Result before ACK:** the terminal result (COMPLETED or inference FAILED) is published
  persistent and `mandatory=True` on a confirm channel; the task is ACKed only after the confirm.
  A return, nack, lost connection or failed ACK escapes to the reconnect loop: the connection is
  closed (releasing unacked work to RabbitMQ) and the worker reconnects after 5 s. Transport
  failures never become an inference FAILED result. Malformed JSON, a non-UTF-8 body or
  excessive nesting is nacked to the task DLQ.
- **Error classification:** only `DetectorError` subclasses (string `code`, deliberate Polish
  message) publish their own code and message. Any other exception, including foreign objects
  with a `code` attribute such as `cv2.error` (`-215`) or libsndfile errors, publishes
  `PROCESSING_ERROR` with the neutral message; details stay in logs. The `failed` outcome
  metric is recorded after the ACK, like `completed`.
- **Object storage outages:** connection errors, timeouts, exhausted transfer retries, 5xx, 429
  and botocore's retryable throttling/timeout codes (`SlowDown`, `RequestTimeout`, …) while downloading the input or uploading artifacts are treated as transport
  failures: no result is published, the connection is closed, and RabbitMQ redelivers the task
  within `x-delivery-limit=5`. Consecutive outages back off the reconnect 5 s → 10 s → 20 s →
  30 s (reset after the next ACKed task), so roughly two minutes of S3 outage are absorbed before
  the task dead-letters to FAILED. These warnings carry only the error type: botocore messages
  include the object URL with the user's file name. Missing or forbidden input (`404`/`NoSuchKey`/`403`) stays
  `FAILED/PROCESSING_ERROR`; a forbidden artifact upload still only drops that heatmap.
- **Task watchdog:** a separate thread checks the running attempt once a second. An attempt
  exceeding `DETECTOR_TASK_HARD_LIMIT_SECONDS` (a hung ffmpeg or native ONNX call cannot be
  interrupted) marks the consumer DOWN and exits the process with status 1; Compose restarts it
  (`unless-stopped`) and RabbitMQ redelivers the task, counted toward the delivery limit. Audio
  ffmpeg extraction additionally runs with `-nostdin` and a timeout of
  `60 + MEDIA_MAX_DURATION_SECONDS / 10` seconds, which fails the task with `PROCESSING_ERROR`.
  The default limit is deliberately above the 600 s stuck-job idle threshold, so a legitimate
  long analysis is never killed; a hung attempt may block the worker up to an hour after
  stuck recovery has already failed the analysis, and its late result is rejected by the guard.
- **Credentials:** `RABBITMQ_PASSWORD`, `S3_ACCESS_KEY` and `S3_SECRET_KEY` have no defaults.
  Missing storage keys stop the import; a missing broker password keeps the API up with
  `/health` DOWN and logs the reason on every reconnect attempt.
- **Isolation of duplicates:** each attempt works in a private temp directory, and Grad-CAM keys
  carry an attempt UUID (`{analysisId}/{source}/gradcam_..._{attempt}.png`), so a duplicate never
  overwrites artifacts referenced by the accepted result.
- **Start ping:** a worker publishes `0/LOADING` before downloading, which flips the analysis to
  `PROCESSING` and records that source's first pickup and latest progress in PostgreSQL.
  Later progress renews only that source's idle clock.
- **Readiness:** models load once at startup. If loading fails, including an import error of the
  ML stack, the process stays up, `/health`
  returns `503 DOWN`, and each task immediately gets a `FAILED` result with code
  `MODEL_UNAVAILABLE` (no download, progress or inference). `/health` is `UP` only with loaded
  models, a connected consumer and a fresh consumer heartbeat. Fix the model and restart the
  container; there is no automatic reload, and Compose does not restart unhealthy containers.

Cancel checks fail open; after a Redis error the worker skips them for 5 s instead of paying
the 1 s socket timeout on every progress tick.

| Variable | Default | Meaning |
|---|---|---|
| `DETECTOR_TASK_HARD_LIMIT_SECONDS` | `3600` | Positive finite limit for one attempt before the worker restarts |

Process kill before publishing → RabbitMQ redelivers and another worker recomputes. Kill after
confirm but before ACK → the result may be published twice. A network loss is detected through
the 30 s heartbeat (allow about a minute) plus the 5 s reconnect delay; this must fit inside the
stuck-job idle budget below.

Tests: `detector-runtime/tests/` (shared delivery guards, `test_failures.py` for error
classification, storage outages and the watchdog), `tests/worker_redelivery/`
(real worker processes with RabbitMQ and Redis; inference is
stubbed, including S3 outage redelivery and the DLQ after the delivery limit), `WorkerRedeliveryIntegrationTest`, `*/tests/test_health.py`.

## Media duration and audio progress

`MEDIA_MAX_DURATION_SECONDS` (positive finite seconds, default 1800) is shared by
file-service and both detectors in Compose. Upload validation rejects a known
duration above this limit before writing S3 or PostgreSQL, using `422 INVALID_FILE`.
Audio checks container duration before extraction and decoded WAV frame count before
allocating samples; an overlong input returns confirmed `FAILED/MEDIA_TOO_LONG` before ACK.
This also protects old uploads or tasks created outside the upload endpoint.

Video checks finite frame-count/FPS duration before reading any frames, then checks
decoded frame count against the same limit on every `grab()`. Reading continues to EOF
instead of trusting the advertised frame count, so understated metadata cannot bypass
the guard. Missing, non-positive or non-finite FPS assumes 60 fps only for the guard
(default 108 000 frames): decoding stays bounded, and ordinary 24–60 fps clips within the
limit are not rejected. Reported timestamps/FPS fall back to zero. An overlong
video returns the same confirmed `FAILED/MEDIA_TOO_LONG` before ACK, and the capture is
released on success and all failures. Both detectors use the shared runtime error class.
FPS metadata above 240 is capped for the guard, so an inflated frame rate cannot grant
an effectively unbounded decoding budget. Genuine clips above 240 fps are therefore limited
to `MEDIA_MAX_DURATION_SECONDS × 240 / fps` seconds.

Video also rejects `width*height` above `MEDIA_MAX_PIXELS` (default 8 294 400 = 3840×2160,
orientation-independent) before decoding any frame, and re-checks each retained decoded
frame because container metadata may be missing or understated. The result is a confirmed
`FAILED/MEDIA_RESOLUTION_TOO_HIGH` before ACK. Retained frames are downscaled to a longest
side of 1920 px right after decoding, so the sampled frames held until face detection stay
bounded in memory (the container limit is 3 GB).

While decoding, video publishes a `PREPROCESSING` progress tick (10–14 %,
`details.step: "extract_frames"`) at most every 5 s of wall-clock time. The tick refreshes
`video_progress_at` for the stuck-job idle budget and checks the `cancel:{id}` flag, so a
cancelled or failed analysis stops decoding within about 5 s instead of after EOF.

| Variable | Default | Meaning |
|---|---|---|
| `MEDIA_MAX_DURATION_SECONDS` | `1800` | Positive finite duration limit for uploads, audio and video |
| `MEDIA_MAX_PIXELS` | `8294400` | Positive integer limit of video `width*height` (4K) |

Unknown container duration remains accepted. Extraction disables video and stops
after at most the limit plus one output sample, so even missing or misleading
duration metadata cannot create an unbounded decoded WAV. The extra sample detects
overflow instead of silently classifying a truncated prefix. Decoded WAVs are removed
after success, extraction/read failures and cancellation; the runtime also removes
the private attempt directory. SIGKILL cannot run cleanup and may leave local files.

Audio keeps every inference/cancellation tick but publishes progress only on a stage
change, one percentage point of progress, or one elapsed second. Final segment and
completion ticks bypass throttling. The gate runs in the shared runtime and is enabled
only by the audio adapter; video progress is not throttled.

Tests: `FileValidatorTest`, `FfprobeMediaProbeIntegrationTest`,
`audio-detector/tests/test_media_limits.py`, `video-detector/tests/test_media_limits.py`
and shared runtime delivery tests.

## Task queue upgrade

`analysis.video` and `analysis.audio` change from classic to quorum. RabbitMQ cannot change
an existing queue's type in place; starting new application code against old queues causes
`PRECONDITION_FAILED`. Keep the broker hostname and volume unchanged. This procedure touches
only these two task queues; it does not purge DLQs, results, progress or broker storage.

1. Block new analysis creation at ingress. Keep the old orchestrator, outbox publisher and
   workers running until all accepted analyses have drained.
   Cancel stuck/crashing analyses through the existing owner cancel API so queued tasks can
   be ACKed without computation. If the backlog cannot be drained, postpone the upgrade;
   deleting a non-empty queue loses tasks and must not be used as migration.
2. Verify no active analyses or unsent outbox tasks for active analyses remain and both task
   queues have zero ready and unacknowledged messages. Disable the outbox publisher and stop
   the orchestrator and both detectors to prevent redeclaration
   and concurrent publication. Check the queue counts again.
3. On the existing broker, delete only the two empty, unused queues:
   `rabbitmqctl delete_queue analysis.video --if-empty --if-unused` and
   `rabbitmqctl delete_queue analysis.audio --if-empty --if-unused` (use the deployment vhost).
   A failed guard means the upgrade must wait; never purge the queue or delete the volume.
4. Deploy the matching orchestrator and shared detector runtime together. Their declarations
   recreate quorum queues and existing bindings. Verify `x-queue-type=quorum`,
   `x-delivery-limit=5` and both DLX arguments, then re-enable dispatch/admission.

Rollback also requires draining and recreating these two empty queues before deploying the old
classic declarations. Nothing deletes these queues automatically.

## Result acceptance and idempotence

Code: `AnalysisService.handleResult`, `AnalysisRepository`.

- Only `video`/`audio` sources and `COMPLETED`/`FAILED` statuses are processed; others are ignored.
- A success is accepted by a conditional `UPDATE` that writes the source probability and details
  only if the analysis is active, its type expects that source, and that source's probability is
  still `NULL`. A non-NULL probability is the durable acceptance marker. Concurrent writers block
  on the row lock and recheck the predicate, so exactly one result per source wins.
- When all expected sources are accepted, a status CAS completes the analysis with
  `0.6·video + 0.4·audio` (FAKE when > 0.5) in the same transaction.
- A `FAILED` result fails the analysis only for an expected, still-unaccepted source. A failure
  arriving after that source succeeded cannot fail a FULL analysis.
- Every terminal transition (complete, fail, cancel, DLQ, recovery) is a CAS on the status, so
  side effects and metrics happen once.
- Redis `dedup:{id}:{source}` (TTL `RELIABILITY_IDEMPOTENCY_TTL_SECONDS`, 900 s) is set after commit
  and only short-circuits obvious duplicates. Missing or lost markers fall back to PostgreSQL.

For FULL only, audio `NO_SPEECH` or `NO_AUDIO_TRACK` may commit an explicit
`NOT_APPLICABLE` marker in audio JSONB details, without an audio score or verdict.
The source CAS accepts only the first score or marker; later audio results and DLQs
cannot replace it even after Redis hint loss. A rollback leaves both PostgreSQL and
Redis acceptance unset. FULL still requires successful video and uses its score
alone for verdict and confidence. AUDIO and video-source errors remain fatal.
The UI and PDF distinguish an absent audio track from audio with no model evidence.

Audio checks streams with ffprobe before extraction. Only successful JSON output
with an empty audio-stream list produces `NO_AUDIO_TRACK`; corrupt input, invalid
output, timeout and extraction failure remain generic processing errors. Shared
runtime failures without a domain code publish a neutral message rather than raw
exception text containing private paths; internal logs retain the exception.

Tests: `AnalysisResultAcceptanceIntegrationTest`, `AudioScoreContractIntegrationTest`,
`ReportPdfServiceTest`, `audio-detector/tests/test_inference_extract.py` (including
generated temporary media with real ffmpeg/ffprobe), `detector-runtime/tests/test_delivery.py`
and frontend result/failure regressions.

| Variable | Default | Meaning |
|---|---|---|
| `RELIABILITY_IDEMPOTENCY_TTL_SECONDS` | `900` | Post-commit per-source Redis dedup hint TTL |

## Listener retries, DLQs and quarantine

Code: `RabbitConfig`, `AnalysisResultListener`, `DeadLetterListener`, `ConfirmedMessageRecoverer`,
`AmqpRecoveryControl`. Queue arguments: [AMQP contract](contracts/amqp-messages.md).

- **Results** (`analysis.results`, AUTO ack after commit): permanent processing failures get
  4 attempts with 1 s / 4 s / 15 s delays
  (`spring.rabbitmq.listener.simple.retry`, `max-retries: 3`), then confirmed republish to
  `analysis.results.dlq`.
  Invalid `analysis_id`, missing/malformed `result.prob_fake`, invalid scores,
  class casts, illegal arguments (including number parsing), Bean Validation and AMQP/messaging
  conversion errors bypass these retries and go directly
  through the confirmed results DLQ recoverer. Payload validation runs before dedup/SQL.
- **Progress** is advisory: 3 attempts (100 ms, 200 ms), then logged and ACKed. It never fails an
  analysis. The `PROCESSING` heartbeat commits before cache eviction and SSE.
- **DLQs** (`analysis.results.dlq`, `analysis.video.dlq`, `analysis.audio.dlq`): fail an active
  analysis only for an expected, unaccepted source (task DLQs take the source from the queue).
  Permanent data failures get 3 attempts (250 ms, 500 ms). Poison messages and exhausted failures go to
  `analysis.backend.quarantine`, which has no consumer and is for manual inspection.
  A `COMPLETED` result without a result object, but with a valid analysis ID and source,
  first commits the source-specific failure, then parks a confirmed copy in quarantine.
  Unidentifiable payloads and legacy advisory progress are quarantined without failing an analysis.
  Terminal failures expose only `[DEAD_LETTERED] Nie udało się zakończyć analizy.`;
  exception details remain in logs and diagnostic AMQP headers.
- **Database outages** on results and terminal DLQs are not exhausted into a recovery queue.
  `CannotCreateTransactionException`, `CannotGetJdbcConnectionException`,
  `DataAccessResourceFailureException`, `TransientDataAccessException`, `QueryTimeoutException`
  SQLState `08xxx`/`57P01`–`57P03` (connection lost, server shutdown or restart) and a failed
  rollback (the session died mid-statement) anywhere in the cause chain bypass in-process retry. The original delivery is requeued,
  its listener is stopped, and restart waits for shutdown completion with 1 s / 2 s / 4 s
  exponential delays capped at 30 s, without an attempt limit. Successful processing resets
  the backoff. Results and DLQs pause independently; progress retains its advisory policy.
  Redis exceptions anywhere in the cause/application-exception chain are excluded before
  examining shared Spring DAO types or SQLState. Optional Redis operations remain fail-open.
  Recovery does not extend analysis deadlines: a long outage can still make an active analysis
  eligible for stuck recovery after PostgreSQL returns. Whichever terminal CAS commits first
  wins; a retained late result cannot revive FAILED/CANCELLED records. On return, the next
  restart may take up to 30 s plus DB connection acquisition and transaction time.
- **Recovery publications** (results DLQ, quarantine) are persistent, mandatory and confirmed. If
  confirmation fails, the original is requeued, the affected listener is stopped and restarted
  after a delay doubling from `AMQP_RECOVERY_DELAY` to `AMQP_MAX_RECOVERY_DELAY`, with no attempt
  limit. The `amqpRecovery` health contributor keeps `/actuator/health/readiness` DOWN until a
  delivery or recovery publication succeeds again.

| Variable | Default | Meaning |
|---|---|---|
| `AMQP_CHANNEL_RPC_TIMEOUT` | `1s` | Channel RPC bound |
| `AMQP_RECOVERY_CONFIRM_TIMEOUT` | `2s` | Confirm wait for recovery publications |
| `AMQP_RECOVERY_DELAY` | `5s` | First listener restart delay |
| `AMQP_MAX_RECOVERY_DELAY` | `60s` | Restart delay cap |

Tests: `AmqpProgressCommitIntegrationTest` stops PostgreSQL for more than 40 s and verifies
that results and terminal DLQs survive until database recovery; it also covers malformed
success → one result attempt → source failure + quarantine. `AnalysisResultRetryIntegrationTest`
verifies no in-process retry for deterministic exceptions while keeping retry metrics intact.
Also: `ResultPayloadValidationTest`, `AmqpFailurePolicyTest`, `DeadLetterListenerIntegrationTest`,
`AmqpRecoveryControlTest`.

### Operator runbook: DLQ and quarantine

Use the RabbitMQ administrator through the loopback management UI
(`http://localhost:15672`, or `RABBITMQ_UI_PORT`) for inspection. Application users
have deliberately restricted permissions. Never grant detectors access to backend
DLQs, change queue arguments, remove retry/dead-letter policies or manipulate analysis
rows to make a replay succeed.

1. Identify the exact Compose project and queue. Inspect queue counts/consumers with
   `docker compose -p <project> exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers`.
   `analysis.backend.quarantine` has no consumer; the three terminal `*.dlq` queues
   are normally drained by the orchestrator. If a stable inspection is needed, stop
   that project's orchestrator during a maintenance window; new analyses will be
   unavailable until it starts again. Do not stop or purge another project's queues.
2. In Queues → the exact queue → Get messages, inspect a small batch with **Ack mode:
   Nack message requeue true** (`ack_requeue_true` in the HTTP API). This puts inspected
   copies back; message order may change. Do not use auto-ack or a non-requeue mode for
   inspection. Store the original payload bytes (base64 for binary bodies), properties,
   headers and queue name in a restricted incident archive before any destructive action.
   Inspect `analysis_id`, `source`, `correlation_id`, `x-death`, `x-original-routingKey`
   (also legacy `x-original-routing-key`) and `x-exception-*`. Treat bodies and error
   details as sensitive; never paste them into public issues or logs.
3. Check the analysis through its owner's API or a read-only database query. Terminal
   states cannot be revived: a late result cannot replace FAILED/CANCELLED, and the
   first accepted result per source wins. Database outages belong in the existing
   requeue/pause path, never in a manual DLQ loop. Fix the root cause (credentials,
   declaration mismatch, invalid payload or application bug) before considering replay.
4. Prefer starting a **new analysis through the normal API**, with the owner's consent,
   after fixing the cause. This uses admission limits and the transactional outbox.
   Do not publish task messages by hand, remove cancel flags or reset database statuses.
   A malformed/unidentifiable body or legacy advisory progress is evidence to archive
   and discard, not a result to manufacture.
5. For a valid retained **result** of an analysis that is still active and whose source
   is expected and unaccepted, an operator may replay the unchanged body/properties to
   `analysis.exchange` with routing key `analysis.results`. For a retained **terminal
   dead letter** whose intent is to fail that source, use `analysis.dlx` with its
   original `analysis.video.dlq`, `analysis.audio.dlq` or `analysis.results.dlq` key.
   Determine the original queue from archived metadata and headers; quarantine alone
   is not enough. Never republish to quarantine hoping it will drain, and never replay
   advisory progress into a terminal queue.
6. Replay needs an AMQP maintenance client with manual ACK, persistent delivery,
   `mandatory=true` and publisher confirms/return handling: retain the original
   unacknowledged delivery, publish one copy, wait for ACK with **no return**, then ACK
   the original. On a return, NACK, timeout or disconnect, keep/requeue the original.
   The UI's publish success alone does not provide this consume→confirm→ACK handoff.
   No such replay tool ships here; if it is unavailable, retain the message and use a
   new analysis instead. A crash between confirm and ACK may duplicate a delivery;
   existing database idempotency and terminal CAS absorb it.
7. Delete only after an archived copy and the recorded operator decision exist. A
   selected message can be manually ACKed without replay after validation. Queue-wide
   **Purge** removes every ready message and is appropriate only when every entry has
   been reviewed and no publisher/consumer can race; it does not remove in-flight
   deliveries. Never delete the queue/exchange. Restart the orchestrator, verify its
   readiness and inspect DLQ/quarantine depth and analysis state before closing the
   incident. Keep unresolved messages parked rather than discarding them to silence
   `AnalysisDeadLetterBacklog`.

## Stuck-job recovery

Code: `StuckJobRecoveryService`, `AnalysisRepository.failIfExpired`.

The first scheduled scan waits `reliability.stuck-job.initial-delay`
(`RELIABILITY_STUCK_JOB_INITIAL_DELAY`, default the configured idle threshold: 600 s).
This gives result/progress listeners time to drain the backlog after a restart. The usual
`RELIABILITY_STUCK_JOB_SCAN_INTERVAL_MS` (300 s) delay then applies between scans.

An active analysis is failed when any of these conditions holds:

- it still has an unsent outbox task and was created more than
  `RELIABILITY_OUTBOX_DISPATCH_TIMEOUT_SECONDS` (120 s) ago, regardless of sibling progress; or
- all its tasks are sent (or it has no outbox rows), an expected, unaccepted source has never
  sent progress, and its own `sent_at` is older than
  `RELIABILITY_STUCK_JOB_QUEUE_TIMEOUT_SECONDS` (1800 s); or
- all its tasks are sent (or it has no outbox rows), an expected, unaccepted source has started,
  and its own last progress is older than `RELIABILITY_STUCK_JOB_THRESHOLD_SECONDS` (600 s); or
- `created_at` is older than `RELIABILITY_STUCK_JOB_MAX_AGE_SECONDS` (7200 s), regardless
  of dispatch state or recent progress/start pings.

The first valid progress switches PENDING to PROCESSING and sets `video_started_at` or
`audio_started_at` once. Each source owns its `video_progress_at` / `audio_progress_at` clock;
repeated start pings never replace its first pickup. A FULL analysis keeps the queue allowance
for an unstarted sibling after the other starts or completes. Conversely, a live sibling cannot
hide silence from a started source. Accepted probabilities and audio NOT_APPLICABLE markers
exclude that source from recovery and later progress; invalid or unexpected sources do not
refresh any clock. The absolute age never changes.

Migration V7 preserves `updated_at` as the pickup/progress time for existing single-source
PROCESSING rows. The old shared clock cannot identify which FULL source started, so existing
FULL rows use each source's dispatch-based queue allowance until new progress arrives.
Legacy rows without outbox entries use `updated_at` as the queue baseline. No tasks are replayed.

The scan is only a candidate list: the final transaction locks the row and rechecks all
conditions with a fresh timestamp. These are eligibility deadlines; the actual failure happens
on the next scan. All terminal states remain protected by the status CAS. A recovered analysis
stores `[ANALYSIS_TIMEOUT] Analiza trwała zbyt długo i została przerwana.`

Tests: `TaskDeliveryLimitIntegrationTest` proves actual connection loss increments the counter
on RabbitMQ 4.3.6 before testing production queue arguments. `WorkerRedeliveryIntegrationTest`
kills each source's worker six times, verifies DLQ → FAILED, skips queued sibling work and
completes a subsequent healthy task. `TransactionalDispatchIntegrationTest` verifies queue,
idle and absolute deadlines plus rollback/commit ordering of all FAILED stop hints.
`AnalysisStuckRecoveryIntegrationTest` checks source guards and first-pickup preservation;
`AnalysisServiceFailFromDlqTest` checks the DLQ failure path;
`StuckJobRecoverySchedulingTest` exercises the real scheduler's startup delay;
`FlywayMigrationIntegrationTest` upgrades populated V6 data to V7.

| Variable | Default | Meaning |
|---|---|---|
| `RELIABILITY_STUCK_JOB_THRESHOLD_SECONDS` | `600` | Started source idle allowance |
| `RELIABILITY_STUCK_JOB_QUEUE_TIMEOUT_SECONDS` | `1800` | Dispatched source pickup allowance |
| `RELIABILITY_STUCK_JOB_MAX_AGE_SECONDS` | `7200` | Absolute active analysis age allowance |
| `RELIABILITY_STUCK_JOB_SCAN_INTERVAL_MS` | `300000` | Delay between recovery scans |
| `RELIABILITY_STUCK_JOB_INITIAL_DELAY` | idle threshold as a duration (`600s`) | Initial scan delay after process start |

## Cancellation

Every successful status CAS to `FAILED` or `CANCELLED` sets Redis `cancel:{id}` (TTL 2 h) after
commit through the shared terminal path. This covers stuck recovery, DLQs, source errors and
`DELETE /api/analysis/{id}` (user cancel). Rollbacks and losing CAS attempts set no stop flag;
COMPLETED sets none. `DELETE /api/analysis/{id}/record` keeps the flag: after the delete commits it
sets or refreshes `cancel:{id}` (TTL 2 h) for a FAILED or CANCELLED analysis, because tasks queued
before the delete still need the stop signal. It removes the cache and `dedup:` keys,
plus legacy `progress:` keys from older
versions. Numeric progress is delivered only over SSE; reconnects use the database
status, and the backend no longer writes unused Redis progress snapshots. The delete
is conditional (`id`, owner, terminal status), so a concurrent duplicate gets
`404` and does not enqueue artifact cleanup again. Detectors check the flag when they take a
task, on each progress tick and before publishing a successful result; on a hit they ACK without a result. Cancellation is not an
AMQP message. If Redis is down the worker finishes and its late result is rejected by the status
guard. Publication, cancel, results and recovery all serialize on the analysis row, so a cancel
that commits first prevents dispatch.

## Analysis cache consistency

Terminal transitions and partial source acceptance evict the analysis cache after commit;
a rollback leaves the existing cache intact. Delete also evicts only after commit.
A GET captures a local fill token before consulting Redis or reading PostgreSQL. Eviction
invalidates that token, and cache writes serialize with eviction, so a delayed GET that read
an older row cannot refill the cache after completion, failure, cancellation or deletion.
The overlapping GET may finish with the snapshot it already read; subsequent GETs use the
committed state, or return 404 after deletion.

The fill guard uses 64 fixed stripes and the existing Redis outage namespace, with no per-ID
map or persistent tombstones. A stripe collision can suppress an unrelated in-flight fill,
causing a later DB cache miss. Redis failures still fall back to PostgreSQL. This guard assumes
the deliberately single orchestrator instance; it is not a distributed cache lock.

Tests: `AnalysisServicePushAfterCommitTest` checks commit/rollback ordering;
`AnalysisServiceCacheIdorTest` pauses a DB read, completes cancellation or deletion through
the service, then releases the stale reader against real Redis and checks the next GET.

## Redis failure budgets

Code: `OptionalRedisOperations` (orchestrator), `ProtectiveRedisRateLimiter` and
`RedisLimiterDegradation` (gateway).

- **Orchestrator:** after an actual Redis failure, optional operations return their fallback
  without calling Redis for `REDIS_DEGRADED_BYPASS`; then Redis is tried again. Fallbacks: cache →
  database, dedup → database guard, cancel flag → worker finishes.
  Each process start and each failure switches to a new cache namespace, so a cache entry that
  missed its eviction is never served again (old entries expire with their 60 s TTL). This assumes
  the single orchestrator instance.
- **Gateway hybrid rate limiter:** `file-service-upload` deliberately fails closed: a Redis
  error, whole-call timeout or Spring Cloud Gateway's `remaining=-1` fallback sentinel returns
  `503` before forwarding. `file-service` and `orchestrator` fail open on these failures, so file
  metadata, analysis reads/listing, SSE and analysis creation remain available (PostgreSQL still
  enforces admission limits). A healthy Redis bucket that is exhausted still returns `429` on
  every route.
  Both limiter beans share one per-process outage window. After a failure, neither calls Redis
  for `GATEWAY_REDIS_DEGRADED_BYPASS`: upload immediately gets `503`, and other routes immediately
  forward without `X-RateLimit-*` headers. The next request after expiry tries Redis again; a
  failure opens another window. Concurrent failures do not extend an existing window. A WARN
  is emitted at most once per window, without a user identifier. Each fail-open request, including
  those in the window, increments `gateway_ratelimiter_degraded_total{route}` (uploads do not).
  On `ApplicationReadyEvent` the gateway runs the limiter script once on a throwaway key
  (`RateLimiterWarmup`, 5 s, failures only logged). Without it the first scripted calls on a cold
  JVM exceeded `REDIS_COMMAND_TIMEOUT`, opened the shared window and failed uploads with `503`.
  Rerun `GatewayRateLimitIntegrationTest` after upgrading Spring Cloud Gateway.

| Variable | Default | Meaning |
|---|---|---|
| `REDIS_CONNECT_TIMEOUT` | `250ms` | Gateway/orchestrator connect timeout |
| `REDIS_COMMAND_TIMEOUT` | `250ms` | Gateway/orchestrator command timeout |
| `REDIS_DEGRADED_BYPASS` | `2s` | Orchestrator bypass after a failure |
| `GATEWAY_REDIS_LIMITER_BUDGET` | `750ms` | Whole gateway limiter call |
| `GATEWAY_REDIS_DEGRADED_BYPASS` | `2s` | Shared gateway Redis bypass after a failure (positive duration) |

Tests: `RedisDownDegradationIntegrationTest`, `GatewayRateLimitIntegrationTest`,
`ProtectiveRedisRateLimiterTest`. The gateway integration test stops/starts real Redis, checks
forwarding to a stub backend, immediate bypass responses and the Prometheus counter, then verifies
`429` returns after recovery. Unit tests cover the sentinel, timeouts and shared window expiry.

## SSE delivery

Code: `AnalysisStreamRegistry`, `SseMvcLifecycle`, `SseLimits`.

Single-instance routing. Producers (progress listener, after-commit result callbacks) only write
into a fixed per-connection mailbox: latest video progress, latest audio progress, one terminal
result and a heartbeat flag. Each connection has one virtual-thread writer, the only code that
writes or completes the response, so a slow client cannot block the listeners or other clients.
The terminal event takes priority and clears pending progress. Stage/status strings longer than
512/32 characters are dropped. A write slower than `SSE_SLOW_WRITE` stops further routing to that
connection.

The dedicated `sse-watchdog` runs heartbeat and observation tasks with independent exception
guards. A failed tick logs WARN and later ticks continue instead of being cancelled by
`scheduleAtFixedRate`.

Quotas count physical connections; over quota → `429` with `Retry-After: 5` and no body.
Missing/foreign subscriptions return an empty `404` without WARN, including callers
accepting only `text/event-stream`. Async disconnects/timeouts write no error body
and do not log ERROR. A slot is released
only after the servlet request completes and the writer exits, so a client that stalls a write
keeps its slot until the peer disconnects. Size the quotas accordingly. The frontend reconciles
with `GET` after a result, EOF, error or `429`; the database is authoritative.

Frontend watching (`frontend/src/api/watch-analysis.ts`, `stream.ts`):

- A stream with no bytes at all (heartbeats included) for 45 s is treated as half-open and
  aborted; the client then reconciles as after an error.
- A stream that stayed open for at least 60 s and ends without a result (normally the
  `SSE_LIFETIME` close) is followed by one `GET` and an immediate reopen.
- After a result, or an earlier EOF or error, the client polls `GET` up to 9 times over 91.5 s
  (longer than the 60 s cache TTL). If the analysis is still `PENDING`/`PROCESSING`, it is not
  shown as failed:
  the client reopens SSE with a 5 s → 30 s backoff (opening SSE replays a terminal result from the
  database) and does one `GET` whenever a reopened stream ends without a result. It stops at a
  terminal status or at `createdAt + 7200 s`, the absolute limit enforced by stuck recovery.
- Reaching the existing absolute age limit raises a distinct client error. Both result and
  upload screens explain that the analysis waiting time expired, rather than reporting a
  connection loss. This stops observation only; the backend remains authoritative for status.
- The upload screen switches to "analysis still running" with a link to the result page instead
  of offering a second upload. Cancel treats `200` and `409` as settled (`409` reads the analysis
  and routes by its status); other errors and a 10 s timeout are shown in place and the stream
  stays open.

| Variable | Default | Meaning |
|---|---|---|
| `SSE_GLOBAL_CONNECTIONS` | `128` | Connections per process |
| `SSE_USER_CONNECTIONS` | `4` | Connections per user |
| `SSE_ANALYSIS_CONNECTIONS` | `4` | Connections per analysis |
| `SSE_LIFETIME` | `10m` | Async request lifetime |
| `SSE_HEARTBEAT` | `15s` | Heartbeat comment interval |
| `SSE_SLOW_WRITE` | `5s` | Write duration that detaches a connection |

Tests: `SseHttpIsolationTest`, `SseBackendAcceptanceIntegrationTest`, `AnalysisStreamWatchdogTest`.

## Grad-CAM artifacts

**Download** (`ArtifactAuthorizationService`, `ArtifactService`,
`BoundedArtifactResponseTransformer`). The owner and recorded-key check runs in a short read-only
transaction that ends before S3 is contacted, so slow storage never holds a database connection.
The body is read inside the SDK timeout scope and capped at `ARTIFACT_MAX_BYTES`; failed or
oversized reads abort the connection. Unknown, foreign or missing → `404`; timeout, read failure or
oversize → `503`.

**Cleanup** (`ArtifactCleanupStore`, `ArtifactCleanupWorker`). `DELETE /api/analysis/{id}/record`
writes every recorded Grad-CAM key to `artifact_cleanup` in the same transaction as the delete; no
S3 call runs on the request. A worker on its own scheduler claims due keys
(`FOR UPDATE SKIP LOCKED`, lease with token), deletes from S3 outside any transaction, then removes
the row. Failures retry with exponential backoff and no limit; missing objects count as deleted.

Orphan discovery lists only `analysis-artifacts`, one page per run, with the cursor stored in
`artifact_cleanup_scan`. The default page holds up to 1000 objects (one full `ListObjectsV2`
page) per 60 s scan. An object is queued only if it is older than
`ARTIFACT_CLEANUP_ORPHAN_AGE`, matches the current attempt-key format exactly, and is neither
under an active analysis nor referenced by any analysis (checked again before deletion). Uploads in
`deepfake-uploads` belong to file-service and are never touched.

| Variable | Default | Meaning |
|---|---|---|
| `ARTIFACT_MAX_BYTES` | `8388608` | Maximum artifact body (8 MiB) |
| `ARTIFACT_STORAGE_CALL_TIMEOUT` | `5s` | Whole S3 call, including retries and body |
| `ARTIFACT_STORAGE_ATTEMPT_TIMEOUT` | `2s` | One S3 attempt |
| `ARTIFACT_STORAGE_CONNECT_TIMEOUT` | `500ms` | Connection and pool acquisition |
| `ARTIFACT_CLEANUP_ENABLED` | `true` | Run deletion and discovery (work is still queued when off) |
| `ARTIFACT_CLEANUP_POLL_INTERVAL_MS` | `1000` | Delay between deletion batches |
| `ARTIFACT_CLEANUP_SCAN_INTERVAL_MS` | `60000` | Delay between listing pages |
| `ARTIFACT_CLEANUP_BATCH_SIZE` | `10` | Deletions per batch (1–100) |
| `ARTIFACT_CLEANUP_PAGE_SIZE` | `1000` | Objects per listing page (1–1000) |
| `ARTIFACT_CLEANUP_ORPHAN_AGE` | `24h` | Minimum orphan age |
| `ARTIFACT_CLEANUP_LEASE` | `30s` | Claim lease; must exceed the S3 call timeout + 1 s |
| `ARTIFACT_CLEANUP_RETRY` | `5s` | First retry delay |
| `ARTIFACT_CLEANUP_MAX_RETRY` | `1h` | Retry delay cap |

Tests: `ArtifactDownloadIntegrationTest`, `ArtifactCleanupIntegrationTest`,
`ArtifactCleanupSeaweedIntegrationTest`, `ArtifactCleanupDefaultsTest`.

## Upload media probing

Code: file-service `FileValidator`, `FfprobeMediaProbe`. The MIME detected by Tika from magic
bytes must be whitelisted, then ffprobe must report an allowed container. ffprobe is asked only
for stream indexes and the container format name and duration (no tags). Its output is capped at
`MEDIA_PROBE_MAX_OUTPUT_BYTES` (default 65536), and stdout reading plus process exit share a 10 s
deadline. Overflow, malformed JSON, non-zero exit, unsupported format or missing stream
data → `422 INVALID_FILE`. Timeout, interruption, process startup I/O failure and stdout
I/O failure → `503 SERVICE_UNAVAILABLE` with `Retry-After: 5`. These operational failures
log WARN without file paths or user data. The temporary upload is removed and no S3
object or metadata is stored.
Cleanup kills the child and waits at most 5 s in total for process exit and reader termination,
restoring the caller's interrupt flag. Failure to exit or stop the reader logs WARN; a still-live
child's pipes are not closed synchronously because a blocked read can hold their lock. The reader
is a daemon so a stuck subprocess cannot keep the JVM alive. The file limit is 500 MiB;
the request limit is 501 MiB to reserve room for multipart headers and boundaries.

Tests need `ffmpeg` and `ffprobe` installed: `FfprobeMediaProbeIntegrationTest`,
`FfprobeMediaProbeProcessTest`, `FfprobeMediaProbeFailureTest`. `FfprobeUploadHttpTest`
checks real child-process timeout/non-zero exit, HTTP status, retry headers and spool cleanup.

## Upload admission and unused retention

Upload admission has three bounds: the gateway token bucket (ten/minute, burst ten; its `429`
carries `Retry-After` = seconds until one upload fits), a servlet filter before multipart parsing (four concurrent uploads per replica, at most
two per authenticated `jwt.sub` on that replica), and
a PostgreSQL per-owner quota (5 GiB). Gateway Redis outages retain their existing
fail-closed 503 policy; reads retain fail-open behavior. Replica saturation returns
503 SERVICE_UNAVAILABLE with Retry-After: 5 and an ErrorResponse body. Per-user saturation
returns 429 UPLOAD_USER_LIMIT with the same retry header. The user slot is acquired
first and released even if the global admission fails; per-user counters are updated
atomically and removed at zero. Spring Security runs before the admission filter. Anonymous
multipart requests (they can reach `permitAll` routes such as actuator health) take only the
replica slot, never a user slot. The filter covers every multipart request,
including unknown routes, other methods and mixed-case content types: MVC parses
multipart before selecting a handler. The slot is released after successful or failed
request processing. A rejected body is still drained (Tomcat `max-swallow-size` 501MB, equal to
the multipart request limit, also enforced by the gateway): with Tomcat's 2 MB default the connection was reset mid-body and the gateway
answered 500 instead of the 429/503 (about 3 % of rejections in the 2026-10-16 soak test). Upload spool uses a dedicated directory; transferTo(File) moves
Tomcat's disk part for validation and S3, and the file is removed in finally. ffprobe
allows only the file protocol and checks each stream's pixel product against 4K.

FileStorageService first rejects an obvious overflow without a lock, then writes the
object to S3 outside any transaction. Admission is a short READ_COMMITTED transaction:
pg_advisory_xact_lock(114546, hashtext(user_id)), the sum of size_bytes of all stored
rows, and the metadata insert; overflow returns 409 STORAGE_QUOTA_EXCEEDED. Exact quota
fits. Concurrent uploads of one owner therefore write to S3 in parallel and serialize only
on the quota check, so replicas cannot spend the same space twice and a slow S3 write never
holds the lock or a pooled connection. A rejection before the admission callback finishes
(including quota rejection) deletes the object; if deletion fails, the orphan sweep removes it.
Once the insert returns, a transaction exception can mean the commit succeeded but its response
was lost. Such uncertain outcomes retain the object and propagate the original error. The
existing orphan sweep checks metadata references after its minimum age (24 h by default):
it preserves committed uploads and reclaims only unreferenced objects. No immediate absence
check is used, since the database can still be unavailable or the commit pending.
An overflow that slips past the early
check is briefly stored in S3. Hash collisions only serialize unrelated owners. Soft-deleted files keep counting until the purge removes the object
and row (72 h by default), so delete-and-reupload cannot exceed the quota on disk.

Creation resolves input through POST /api/files/{id}/claim with the verified JWT.
The atomic update sets analyzed_at only when absent and requires the owner and an
active row. Cleanup atomically soft-deletes rows with analyzed_at IS NULL older
than seven days, then uses the existing 72-hour purge. These updates serialize on
the file row: a committed claim excludes expiry; expiry makes a later claim 404.
Atomic explicit soft-delete also avoids overwriting a concurrent claim timestamp.
Claim commits before orchestrator admission: later 429, failure or a timed-out
response can leave a file claimed without an analysis. There is no cross-service
transaction. Historical rows are conservatively marked used by migration V3 to
protect existing analysis references; unused retention applies to new uploads.

| Variable | Default | Meaning |
|---|---|---|
| `STORAGE_QUOTA_BYTES` | `5368709120` | Active uploaded bytes per user (5 GiB) |
| `UPLOAD_UNUSED_RETENTION` | `7d` | Age before an unclaimed upload is soft-deleted |
| `UPLOAD_MAX_CONCURRENT` | `4` | Upload requests per replica, including multipart parsing |
| `UPLOAD_MAX_CONCURRENT_PER_USER` | `2` | Concurrent upload requests per authenticated jwt.sub on each replica |
| `UPLOAD_SPOOL_DIRECTORY` | `${java.io.tmpdir}/deepfake-uploads` (Compose: `/tmp/deepfake-uploads`) | Dedicated multipart and validation spool |
| `MEDIA_MAX_PIXELS` | `8294400` | Maximum width×height of each video stream (4K) |
| `UPLOAD_RL_REPLENISH` | `1` | Upload tokens replenished per second |
| `UPLOAD_RL_BURST` | `60` | Upload bucket token capacity |
| `UPLOAD_RL_REQUESTED_TOKENS` | `6` | Tokens spent per upload (10/minute, burst 10) |

Tests: UploadLifecycleIntegrationTest (real PostgreSQL, concurrent quota admission,
rollback, owner-scoped idempotent claim, expiry), UploadSpoolIntegrationTest (real
Tomcat disk part, one copy and admission before parsing), FileUploadControllerSecurityTest
(names and 409 body), FileValidatorTest/FfprobeMediaProbeProcessTest (4K and pixel
arithmetic), GatewayRateLimitIntegrationTest (real Redis), and
AnalysisFileResolutionIntegrationTest (authenticated POST claim before analysis TX).
