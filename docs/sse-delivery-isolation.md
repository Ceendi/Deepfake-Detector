# Bounded SSE delivery

Analysis SSE is single-instance routing. The owned committed snapshot → registration → fresh committed snapshot ordering remains unchanged. Progress producers and after-commit result callbacks only enqueue in memory; they never write or complete an HTTP response.

## Capacity and delivery

Each admitted connection has one independent virtual writer and a fixed mailbox: latest video progress, latest audio progress, one terminal result, and one heartbeat flag. Terminal delivery takes priority and clears pending advisory updates. Retained advisory stage/status strings are limited to 512/32 characters; oversized advisory events are dropped. Result fields come from the existing bounded database projection. A slow subscriber cannot accumulate an event queue or occupy another subscriber's writer.

| Environment variable | Default | Meaning |
|---|---:|---|
| `SSE_GLOBAL_CONNECTIONS` | 128 | Maximum retained connections/writers in this process |
| `SSE_USER_CONNECTIONS` | 4 | Maximum retained connections for an authenticated owner |
| `SSE_ANALYSIS_CONNECTIONS` | 4 | Maximum retained connections for one analysis |
| `SSE_LIFETIME` | 10m | Servlet async request lifetime |
| `SSE_HEARTBEAT` | 15s | Dedicated heartbeat cadence; an initial comment flushes headers |
| `SSE_SLOW_WRITE` | 5s | Observe a long write and stop routing further events to that connection |

The owned lookup and existing JWT/USER role enforcement happen before admission. Exhausted stream quotas return `429` with the normal error body and `Retry-After: 5`. Quotas count retained physical connections, including terminal subscribers already detached from routing. A dedicated daemon scheduler only offers heartbeat/closure intent; ordinary outbox/recovery scheduling never performs SSE network I/O.

## Initialization and completion

The MVC return-value handler is wrapped to record successful emitter initialization or failure. `AsyncHandlerInterceptor.afterConcurrentHandlingStarted` then starts the connection's writer, after return-value handling. Async-start alone is insufficient: a failed initializer or an already pending asynchronous error cannot arm a writer. Before initialization, replay/progress stays in the fixed mailbox, never Spring's early-send buffer.

Only the connection's writer calls `send`, `complete`, or `completeWithError`. A slow-write observation or timeout removes routing and requests controlled closure. A reservation is released exactly once only after physical Servlet completion **and** writer exit (or if no writer ever started). A failed fresh snapshot never returns an emitter to MVC and releases that unbound reservation without a network operation. Terminal broadcast detaches only its current subscribers; a later subscriber retains its independent terminal replay.

This is bounded isolation, not a whole-send deadline or forced socket abortion. Spring completion can flush/block under its write lock; Tomcat interruption and idle write timeout do not guarantee a total bound when a peer trickles data. A physically blocked writer retains its quota even after timeout or routing removal. Availability is therefore limited if every configured connection slot is occupied by stalled peers; configure caps according to deployment memory/connection capacity. Peer disconnect/error and eventual physical completion allow reuse, without spawning replacement writers for still-blocked connections.

A healthy subscriber receives the terminal event and EOF. An isolated subscriber may instead receive controlled EOF/error after its current write finishes. The existing frontend performs bounded owned GET reconciliation on result, EOF, error or retryable429; it does not automatically reopen SSE. Database state remains authoritative.

## Acceptance

`SseHttpIsolationTest` uses actual Tomcat TCP connections with small receive/write buffers. Three clients read headers then stop reading; a healthy reader and newly admitted subscriber continue receiving results. A forced burst of bounded 512-character advisory stages saturates TCP buffers (approximately25,000–30,000 driver offers in observed runs, coalesced before delivery). This is synthetic transport stress, not normal inference frequency or a claim about exact wire event counts. Ordinary events have small stage names and arrive per detector update, plus idle15-second comments.

The tests require individual writes to remain in flight for at least700ms, then verify fast producer/heartbeat/watchdog offers, HTTP quotas, real peer-close release, and actual Servlet timeout while a peer trickles bytes. Timeout does not recycle blocked capacity. Real MVC tests hold preprocessing after async start, check an empty Spring early-send buffer with a pending terminal replay, and inject failures both before and after async start without orphan writers.

`SseBackendAcceptanceIntegrationTest` runs the production authenticated HTTP controller, PostgreSQL/Redis, and AUTO Rabbit listeners with those stopped readers. It verifies committed progress/result and acknowledged queues, released database connections, an ordinary TaskScheduler marker, healthy/new/late terminal delivery, owned GET reconciliation, rollback isolation, cancelled replay, and401/403/404 before admission. The nine prior committed snapshot/registration race cases remain covered with explicit asynchronous delivery observations. Containers are isolated and no developer data is reset.
