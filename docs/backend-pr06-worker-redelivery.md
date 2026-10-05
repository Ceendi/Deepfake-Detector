# Worker redelivery after process loss (PR06)

Base: `bd0cf1f8311bd8f689d21a4837abf87625800240`. PR01–05 and Dependabot
#91/#92 are already merged. CI, Infrastructure smoke and Security scan all
completed successfully on this base. This change implements only PR06.

## Delivery and acknowledgement

Detector tasks are delivered at least once, with manual ACK and prefetch 1.
Every valid, non-cancelled delivery runs the pipeline, including simultaneous
copies and RabbitMQ redeliveries. There is no pre-inference Redis claim or
completion marker. Existence of `processing:{analysis_id}:{source}` proves
neither that a worker is alive nor that a result reached the broker.

The consumer publishes a persistent terminal result with `mandatory=True` on a
channel using publisher confirms. ACK follows the successful synchronous broker
confirmation. This applies to both COMPLETED and genuine inference FAILED results.
A broker confirm establishes RabbitMQ acceptance, not PostgreSQL commit; the
orchestrator's transactional result listener separately persists the result.

A publish return, broker nack, lost connection or failed ACK escapes to the
consumer reconnect loop. The consumer closes any remaining open connection to
release unacked deliveries, then waits five seconds before reconnecting. Progress
publication failures follow the same path; they do not become inference FAILED
results. There is no explicit immediate nack/requeue loop.

If a process dies before result publication, RabbitMQ redelivers the unacked task
and a replacement computes it again. If it dies after confirm but before ACK,
the replacement may publish another terminal result. PR05 accepts the first
terminal result from an expected source in PostgreSQL. Later success/failure
copies cannot replace accepted source data, change a terminal aggregate, or
release database capacity again. FULL still has independent video and audio
tasks and keeps its existing 0.6/0.4 aggregation. Audio decision scores, raw
metadata, threshold semantics, models and checkpoints are unchanged.

Each attempt uses a private temporary directory for downloads, audio scratch
files and video heatmaps. Parallel copies sharing a host cannot remove or write
one another's local files. Normal completion, failure and cooperative cancel
clean this directory; SIGKILL can leave scratch files until container disposal.

## Redis and cancellation

Redis remains an optional compute-saving cancellation channel. The consumer
checks `cancel:{analysis_id}` before work, on progress ticks, and once more before
publishing a successful result. A hit causes ACK without result publication;
the orchestrator has already committed CANCELLED. A later race with cancellation
is resolved by the orchestrator's PostgreSQL terminal-state guard.

Redis loss, refusal or a non-responding server fails open. Cancellation reads
use one-second connect/read timeouts and explicitly disable client retries;
otherwise newer redis-py defaults can delay every progress tick. These socket
timeouts bound individual network operations, not the entire model runtime.
No detector processing keys are created, read or deleted. Result acceptance
hints `dedup:{analysis_id}:{source}` still belong to the orchestrator and are
optional optimizations after database commit.

## Recovery timing and rollout

No recovery waits for a Redis TTL. Existing one-hour processing markers are
inert immediately in updated consumers and may expire naturally. The removed
video `DEDUP_TTL_SECONDS` setting is ignored; there is no replacement lease TTL.
No Redis flush, key deletion, database migration or task replay is required.

Deploy PR05 before these consumers. Stop the old detector consumers before
starting replacements, or drain them before switching traffic. During a mixed
version rollout, a delivery reaching an old consumer can still be discarded
because of an old processing key. Stopping an old process releases its unacked
work to RabbitMQ; start both updated detector services against the same durable
queues. Preserve Redis cancellation keys and orchestrator result hints.

With a healthy broker, local SIGKILL closes the socket and redelivery is available
immediately to another ready consumer. Network disappearance instead depends on
RabbitMQ's negotiated 30-second heartbeat and missed-heartbeat detection (allow
roughly 60 seconds), plus the existing five-second reconnect backoff and worker
startup. The replacement emits 0/LOADING before downloading, renewing the
orchestrator heartbeat. There is no one-hour marker delay to exceed the default
600-second idle threshold; recovery scans every 300 seconds. A ready replacement
and restored connectivity must still arrive within that idle budget. This PR
does not extend the budget or promise recovery across an outage lasting beyond
it; the existing recovery service can then fail the analysis. Model initialization,
queue backlog and long stages without progress remain subject to that policy.

## Regression evidence and local commands

Install lightweight dependencies into a disposable Python environment:

```sh
python3 -m venv /tmp/pr06-venv
/tmp/pr06-venv/bin/pip install -r audio-detector/tests/requirements-light.txt ruff==0.16.10
(cd audio-detector && AWS_EC2_METADATA_DISABLED=true /tmp/pr06-venv/bin/python -m pytest -q tests)
(cd video-detector && AWS_EC2_METADATA_DISABLED=true /tmp/pr06-venv/bin/python -m pytest -q tests)
/tmp/pr06-venv/bin/ruff check video-detector audio-detector tests/worker_redelivery
DETECTOR_TEST_PYTHON=/tmp/pr06-venv/bin/python tests/worker_redelivery/run.sh
(cd orchestrator && DETECTOR_TEST_PYTHON=/tmp/pr06-venv/bin/python ./mvnw verify)
```

Use Python >=3.12 and JDK 25. Docker must be available to both the shell and
Testcontainers. `run.sh` creates a unique Compose project, dynamically allocated
loopback ports and disposable services; every Compose mutation, including
cleanup and Redis pause/unpause, supplies `-p`. It never operates on the user's
main Compose deployment. Testcontainers creates separate PostgreSQL, RabbitMQ
and Redis instances for Java tests.

`tests/worker_redelivery/worker.py` runs each production `run_consumer`,
`_handle_message`, pipeline and real pika channel. Only inference and S3 input
are replaced by controlled lightweight fixtures. Observability hooks delegate
to the real publish and ACK operations. A `confirmed` barrier is reached only
after the real blocking publish returns; an `acked` barrier follows a synchronous
broker round trip after the ACK frame. Kill barriers use SIGKILL, not simulated
consumer exceptions. Tests assert RabbitMQ's actual `redelivered` flag.

Python process regressions cover both sources: kill before publication with
one-hour legacy markers, kill after confirm before ACK, concurrent copies with
private scratch paths, Redis outage during inference, cancel before and during
work, and mandatory returns on result/progress publications. The outages use a
paused real Redis server, so they also exercise read timeouts rather than only
connection refusal.

`WorkerRedeliveryIntegrationTest` admits FULL through `AnalysisService`, dispatches
with the real RabbitTemplate and consumes results/progress through the real
Spring AMQP listeners. For either restarted source it kills after confirmed
publication, clears only the disposable orchestrator dedup hint, runs a changed
success and a concurrent FAILED duplicate, then completes the independent sibling.
Independent SQL reads check committed source probability/details, preserved raw
audio metadata, final verdict/confidence and the terminal row. Admission at a
one-slot PostgreSQL limit proves capacity stays occupied until FULL completes,
becomes reusable once, and stays occupied by a new analysis after late duplicates.
A listener observation increments only after the real transactional handler
returns, preventing queue-ready counts from hiding uncommitted work.

CI runs lightweight tests for both detectors, process regressions with RabbitMQ
and Redis, and the FULL/PostgreSQL cases in orchestrator `verify`. Synthetic
inference is evidence about delivery and integration, **not** evidence that real
ML checkpoints load or that their predictions are correct. Real checkpoint
loading, full model inference and S3 integration are outside these new tests.

Local execution on 2026-10-05: audio lightweight suite 64 passed, video 36 passed,
ruff 0.16.10 clean, isolated process suite 16 passed, and orchestrator `./mvnw verify`
on JDK 25 passed all 260 tests with none skipped (including both FULL restart cases).
The stale-processing-marker unit regression fails on each detector at the unchanged
base and passes with this change. Earlier test-infrastructure attempts exposed a
RabbitMQ startup-readiness window and redis-py's default retry delays; the final
process suite uses application/port readiness and explicit zero Redis retries.
