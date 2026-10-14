# REST API Contracts

Convention: **camelCase** for REST/JSON (Java/TS-friendly). The Orchestrator
maps snake_case (AMQP) → camelCase (REST) internally — AMQP payloads are
never forwarded to clients as-is.

Base path: `/api`. Auth: Bearer JWT (Keycloak OIDC, PKCE).

`X-Correlation-ID` accepts only `^[A-Za-z0-9-]{1,64}$`; missing or invalid values
are replaced with a UUID. Gateway forwards and echoes the accepted/generated ID.
Orchestrator and file-service apply the same validation on direct requests;
orchestrator forwards the request ID on its file-service metadata lookup.
Gateway CORS accepts the comma-separated `GATEWAY_CORS_ORIGINS` (defaults
`http://localhost:5173,http://localhost:4173`). Allowed preflights are answered
before JWT authentication, with credentials and requested headers allowed.

Both servlet services return `400 VALIDATION_FAILED` for invalid parameter types
(including UUID), missing required parameters/file parts, invalid multipart requests
and unknown property references. Unsupported methods return `405 METHOD_NOT_ALLOWED`
with `Allow`; unsupported content types return `415 UNSUPPORTED_MEDIA_TYPE`.
Unacceptable response types return `406` with no body. Multipart size overflow
returns `413 PAYLOAD_TOO_LARGE`. True unexpected failures retain `500 INTERNAL_ERROR`.
Shared HTTP error messages are neutral Polish text; `ResponseStatusException` reasons
are not echoed. When the caller accepts only a binary/SSE representation, error
responses keep their status/headers with an empty body instead of attempting JSON.
Async disconnects write no further response and are not logged as errors;
async timeouts return an empty `503` when the response is still writable.

## File Service

### `POST /api/files/upload`

Upload a file for analysis.

```
Content-Type: multipart/form-data
Authorization: Bearer <token>
Body: file=@<file>
```

Two-stage validation (magic bytes via Tika, then `ffprobe`) runs before the file
is stored; `mimetype` is the **detected** type, not the client-supplied one.
Rejections: `413` (over the 500 MB limit), `422` (not a whitelisted A/V container —
MP4/MOV/AVI/WAV/MP3/FLAC, invalid duration, or known duration above
`MEDIA_MAX_DURATION_SECONDS`, default 1800 seconds for both audio and video, or
any video stream above `MEDIA_MAX_PIXELS`, default 8294400 = 3840×2160).
Resolution compares width×height regardless of orientation; the `INVALID_FILE` message is
`[MEDIA_RESOLUTION_TOO_HIGH] Rozdzielczość nagrania jest za wysoka (maks. 4K).`
Exactly the limit is accepted. Unknown duration remains accepted; the audio detector
also bounds decoding and checks the decoded WAV before allocating its waveform.
`422` body uses code `INVALID_FILE`; no S3 object or metadata row is stored.

Owner quota: stored `size_bytes` (including soft-deleted files until their physical purge,
72 h by default) plus the upload may not exceed
`STORAGE_QUOTA_BYTES` (5368709120 bytes, 5 GiB). Exact equality is accepted.
Exhaustion returns `409` with code `STORAGE_QUOTA_EXCEEDED` and message
`Przekroczono limit miejsca na pliki (5 GB). Usuń stare analizy – miejsce zwalnia się do 72 h po usunięciu.`
The check is serialized across replicas per owner before the S3 write.
The gateway permits 5 uploads/minute with burst 5 by default (429). A full replica
(`UPLOAD_MAX_CONCURRENT`, default 4) returns 503 with `Retry-After: 5` before multipart parsing.
This guard also covers multipart requests on other paths/methods before MVC routing.

Response `200 OK`:

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "fileKey": "550e8400-e29b-41d4-a716-446655440000.mp4",
  "size": 10485760,
  "mimetype": "video/mp4"
}
```

### `GET /api/files/{id}/metadata`

Metadata for an uploaded file. Returns `200 OK`; `404 Not Found` if the file does
not exist, was soft-deleted, OR `userId != jwt.sub` (IDOR guard — never `403`).

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "test.mp4",
  "size": 10485760,
  "duration": 12.5,
  "mimetype": "video/mp4",
  "objectKey": "550e8400-e29b-41d4-a716-446655440000.mp4"
}
```

`objectKey` is the canonical storage key from the owned, active metadata row.
This additive field lets the orchestrator resolve its input through the authorized
API using the caller's verified Bearer token.

`duration` is the media length in seconds (from `ffprobe`); `null` if unknown.
`name` is the original upload filename without control characters, truncated to 512
Unicode code points, and may be `null`. New storage keys use the UUID plus an
extension from detected MIME; historical keys continue to be read from metadata.

### `POST /api/files/{id}/claim`

Marks an owned active upload as used for analysis. Returns `200 OK` with exactly
the same body as `GET /metadata` (no request body). `404` for missing, soft-deleted
or foreign files. The first `analyzed_at` is retained on repeated claims. Claim and
unused-retention expiry are atomic: expiry cannot delete a claimed upload, and a
claim cannot revive an expired upload. The orchestrator calls claim before its
analysis creation transaction. A later backpressure rejection, timeout or rollback
does not undo an already committed claim.

Unclaimed uploads older than `UPLOAD_UNUSED_RETENTION` (default 7 days) are
soft-deleted by cleanup, then purged after the existing 72-hour deleted-file retention.
Migration conservatively marks historical uploads used because their analysis
references live in the orchestrator database. New uploads start unclaimed.

### `GET /api/files/{id}/presign`

Returns a short-lived (1 h) presigned URL for fetching the file directly from object
storage (e.g. `<video src=...>`). `200 OK`; `404 Not Found` for a missing, soft-deleted,
or non-owned file (IDOR — never `403`). The URL host is browser-reachable (not the
internal storage endpoint).

```json
{
  "url": "http://localhost:8333/deepfake-uploads/550e8400-....mp4?X-Amz-Algorithm=...&X-Amz-Signature=...",
  "expiresAt": "2026-04-21T11:30:00Z"
}
```

### `DELETE /api/files/{id}`

Soft-deletes a file (sets `deleted_at`; the stored object is retained and reclaimed by
a later cleanup job). `204 No Content` on success. Afterwards the file is gone from the
API: metadata/presign and a repeated delete all return `404`. `404` also for a missing or
non-owned file (IDOR — never `403`).

## Orchestrator

### `POST /api/analysis`

Start an analysis for an active uploaded file owned by the authenticated user.

```
Content-Type: application/json
Authorization: Bearer <token>
```

Body:

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "VIDEO"
}
```

`type`: `VIDEO` | `AUDIO` | `FULL`. Response `201 Created`: `Analysis` (see below).
`fileId` must be a UUID (maximum 255 characters); invalid values return `400`
before lookup or capacity admission. The optional audio `mode` remains unchanged
(`fast` or `accurate`, default `accurate`).

Legacy clients may still send `fileKey` (maximum 500 characters), but it is
**ignored**, including when it disagrees with metadata. The orchestrator forwards
the verified Bearer token to `POST /api/files/{id}/claim` and uses only its
canonical `objectKey` for the saved analysis and every detector task. The response's
`fileKey` is that canonical value. `X-User-ID` does not establish identity.

Foreign, missing or soft-deleted files return `404`. Dependency timeout, connection
failure, non-200/non-404 responses, or missing/invalid canonical metadata return
`503` (`SERVICE_UNAVAILABLE`). These failures occur before the writer transaction
and capacity admission: no analysis row, task publication or occupied slot.
The complete HTTP response has a finite deadline (default 2 seconds, no retries).
See [input resolution](../reliability.md#creating-an-analysis).

Capacity is the number of committed `PENDING`/`PROCESSING` rows in PostgreSQL.
Both a global limit (default 20) and a per-authenticated-user limit (default 3) apply;
a FULL analysis occupies one place in each. Both are configurable with
`BACKPRESSURE_MAX_INFLIGHT` and `BACKPRESSURE_MAX_INFLIGHT_PER_USER`.
Admission is serialized by a transaction advisory lock until the create commits or
rolls back. A failed create consumes no capacity, and a terminal update frees capacity
only after commit. Redis availability does not affect this limit. See
[capacity](../reliability.md#creating-an-analysis).

Three distinct `429 Too Many Requests` can occur:

- **Orchestrator backpressure** (global or per-user in-flight analysis limit): body
  `{ "reason": "GLOBAL_LIMIT" | "USER_LIMIT", "limit": <int>, "queuePosition": <int>, "retryAfterSeconds": <int> }`
  plus a `Retry-After` header matching `retryAfterSeconds`. `limit` is the configured
  limit that rejected admission. If both limits are reached, `USER_LIMIT` takes precedence.
  These fields are additive: older bodies without `reason` are treated as global backpressure
  by the frontend. `USER_LIMIT` displays the user's active-analysis limit; global backpressure
  keeps the queue message. `queuePosition` is the global active analysis count plus this
  rejected request, capped at the maximum signed integer.
- **SSE connection admission**: empty body and `Retry-After: 5`; configurable global/owner/analysis connection caps apply after ownership checks.
- **Gateway rate limit** (per-user token bucket): no JSON body; rate-limit state is
  carried in `X-RateLimit-*` headers (Spring Cloud Gateway default).

Gateway Redis failures affect only upload availability: `POST /api/files/upload`
(`file-service-upload`) returns `503` before forwarding, without `X-RateLimit-*`
headers. Other file-service and orchestrator routes (including analysis creation,
reads, listing and SSE) are forwarded on Redis error, timeout or `remaining=-1`
fallback, without rate-limit headers. They still enforce authentication, ownership
and backend admission rules. Both policies share a short Redis bypass window
(default 2 s), so subsequent requests avoid the Redis wait. `429` remains unchanged
when Redis is healthy. Other backend causes of `503` remain unchanged.

### `GET /api/analysis/{id}`

Returns `200 OK` with the full `Analysis` (see below). Returns `404 Not Found`
if the resource does not exist OR `userId != jwt.sub` (IDOR guard — never `403`).

### `GET /api/analysis/{id}/report.pdf`

Downloads the analysis report as a PDF (`Content-Type: application/pdf`,
`Content-Disposition: attachment; filename="report-<id>.pdf"`). `200 OK` for the owner of a
`COMPLETED` analysis. `409 Conflict` if the analysis is not `COMPLETED` (report not ready).
`404 Not Found` for a missing or non-owned analysis (IDOR guard — never `403`). `401` without a
token. Semester 1: verdict/confidence/probabilities/timestamps as text + a table; Grad-CAM heatmap
embeds land in semester 2.

### `GET /api/analysis/{id}/artifacts/{source}/{name}`

Downloads one Grad-CAM artifact (`Content-Type: image/png`) referenced by
`details.gradcamUrls` of the analysis. `source` is `video` | `audio`; `name` must match
the filename of an artifact the detector actually reported for that source — anything
else is `404` (the bucket is never browsable through this endpoint). `404 Not Found`
also for a missing or non-owned analysis (IDOR — never `403`). `503 Service
Unavailable` when object storage is down. Responses carry
`Cache-Control: private, max-age=86400, immutable` (artifacts never change once
written).

### `GET /api/analysis`

Paginated history for the authenticated user. Query params: `page` (default `0`),
`size` (default `20`, maximum `100`); ordered by `createdAt DESC, id DESC`.
The `sort` parameter is ignored, including unknown property names. Returns `200 OK` with a
`PagedModel` of the lightweight `AnalysisSummary` projection — **not** the full
`Analysis` (detail-only fields such as `fileKey`, `videoProb`, `audioProb`,
`errorMessage`, `details` are omitted; fetch them via `GET /api/analysis/{id}`).

```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "fileId": "550e8400-e29b-41d4-a716-446655440000",
      "type": "VIDEO",
      "status": "COMPLETED",
      "verdict": "FAKE",
      "confidence": 0.74,
      "createdAt": "2026-04-21T10:30:00Z",
      "updatedAt": "2026-04-21T10:30:02Z"
    }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

`AnalysisSummary` fields (`id`, `fileId`, `type`, `status`, `verdict`,
`confidence`, `createdAt`, `updatedAt`) follow the same types and nullability as
the matching `Analysis` fields below.

### `GET /api/analysis/stats`

Aggregate stats of the authenticated user's analyses (homepage dashboard). Scoped
to `jwt.sub` by construction (like `GET /api/analysis`), so there is no IDOR
surface. `200 OK`:

```json
{
  "total": 42,
  "byStatus": { "completed": 30, "failed": 5, "cancelled": 2, "inProgress": 5 },
  "byType": { "video": 20, "audio": 10, "full": 12 },
  "verdicts": { "fake": 12, "real": 18 },
  "avgConfidence": 0.83,
  "last7Days": 9,
  "lastAnalysisAt": "2026-06-12T10:30:00Z"
}
```

- `inProgress` = `PENDING` + `PROCESSING`.
- `verdicts` counts `COMPLETED` analyses by verdict (`fake + real == byStatus.completed`).
- `avgConfidence` averages `COMPLETED` analyses only; `null` until the first one completes.
- `last7Days` counts analyses created in the trailing 7 days (any status).
- `lastAnalysisAt` is the `createdAt` of the newest analysis; `null` for a fresh user.

### `DELETE /api/analysis/{id}`

Soft-cancels an in-progress analysis. `200 OK` with the `Analysis` (now
`status: CANCELLED`). Idempotent: cancelling an already-`CANCELLED` analysis also
returns `200`. `409 Conflict` (code `CONFLICT`) if the analysis already finished
(`COMPLETED`/`FAILED`). `404 Not Found` for a missing or non-owned analysis (IDOR —
never `403`). Any open SSE stream (below) receives a `result` event with
`status: CANCELLED` and is then closed. A committed cancel also sets the Redis
flag `cancel:{analysis_id}` (best-effort) so detectors abort queued/in-flight
work early instead of processing the whole file — see the Cancellation section
in [`amqp-messages.md`](./amqp-messages.md).

### `DELETE /api/analysis/{id}/record`

Permanently deletes a **finished** analysis from the caller's history (hard delete — the row is
gone from list/get/stats/stream, not soft-flagged). `204 No Content` on success. `409 Conflict`
(code `CONFLICT`) if the analysis is still in progress (`PENDING`/`PROCESSING`) — cancel it first
(see `DELETE /api/analysis/{id}`), because deleting an active analysis would discard its
state prematurely and race the detector still writing its result. `404 Not Found` for a missing,
already-deleted, or non-owned analysis (IDOR — never `403`); of two concurrent deletes of the same
analysis, one gets `204` and the other `404`.

Distinct route from cancel on purpose: `DELETE /api/analysis/{id}` soft-stops a *running* analysis
(and is idempotent on an already-`CANCELLED` one), whereas this removes a *finished* one — the two
map to different UI affordances and must not collide. The analysis's Grad-CAM objects in
`analysis-artifacts` **are** reclaimed through the durable `artifact_cleanup` queue:
recorded keys are enqueued in the same database transaction as the row delete.
A background worker deletes from S3 after commit and retries failures with bounded
exponential backoff and no attempt limit; missing objects count as deleted. An S3
outage does not block the HTTP delete. Orphan discovery also durably queues eligible
unreferenced objects; see [artifact cleanup](../reliability.md#grad-cam-artifacts). The uploaded file in
`deepfake-uploads` is **not** removed: it lives in another service/bucket the Orchestrator cannot
touch and may be shared by other analyses — its lifecycle is the file service's (see
[`object-storage.md`](./object-storage.md)).

## `Analysis` shape

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "keycloak-sub-uuid",
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "fileKey": "550e8400-e29b-41d4-a716-446655440000.mp4",
  "type": "VIDEO",
  "status": "COMPLETED",
  "verdict": "FAKE",
  "confidence": 0.74,
  "videoProb": 0.87,
  "audioProb": null,
  "details": {
    "video": {
      "modelVersion": "v1.0.0",
      "confidence": 0.74,
      "verdict": "FAKE",
      "gradcamKeys": ["550e8400-e29b-41d4-a716-446655440000/video/frame1.png"],
      "gradcamUrls": ["/api/analysis/550e8400-e29b-41d4-a716-446655440000/artifacts/video/frame1.png"],
      "metadata": { "frames_analyzed": 16 }
    }
  },
  "errorMessage": null,
  "createdAt": "2026-04-21T10:30:00Z",
  "updatedAt": "2026-04-21T10:30:02Z"
}
```

| Field          | Type                                                           | Nullable when                                  |
|----------------|----------------------------------------------------------------|------------------------------------------------|
| `status`       | `PENDING` \| `PROCESSING` \| `COMPLETED` \| `FAILED` \| `CANCELLED` | never                                     |
| `verdict`      | `FAKE` \| `REAL` \| `null`                                     | `status != COMPLETED`                          |
| `confidence`   | `number` (0..1) \| `null`                                      | `status != COMPLETED`                          |
| `videoProb`    | `number` (0..1) \| `null`                                      | source not analyzed                            |
| `audioProb`    | `number` (0..1) \| `null`                                      | source not analyzed                            |
| `details`      | `object` \| `null`                                             | no detector has reported yet                   |
| `errorMessage` | `string` \| `null`                                             | `status != FAILED`                             |

Backend-generated failures use these neutral `errorMessage` values:

- DLQ: `[DEAD_LETTERED] Nie udało się zakończyć analizy.`
- Stuck recovery: `[ANALYSIS_TIMEOUT] Analiza trwała zbyt długo i została przerwana.`

Broker exception headers and internal recovery details are never exposed in those messages.

For completed FULL analyses whose audio returned `NO_SPEECH` or `NO_AUDIO_TRACK`, `audioProb` remains
null and `details.audio` records an explicit source outcome:

```json
{"status": "NOT_APPLICABLE", "reason": "NO_SPEECH", "message": "..."}
```

`reason` is `NO_SPEECH` or `NO_AUDIO_TRACK`, matching the accepted audio error code.
This marker has no audio verdict, confidence, model version, metadata or artifact
arrays. FULL waits for successful video and then uses the video score alone for its
verdict and distance confidence; completed UI/PDF explain that basis. A successful
two-source FULL retains its existing 0.6/0.4 aggregation. AUDIO `NO_SPEECH` and `NO_AUDIO_TRACK` remain
FAILED; all other unaccepted required-source failures remain fatal for FULL.

`details` groups per-source detector results under `video` / `audio` keys (only the
sources that have reported are present). Ordinary result entries may carry `modelVersion` (string),
`confidence` (number 0..1 — the **detector's own** confidence, computed with its
specific threshold and formula; distinct from the top-level `confidence` which is the
Orchestrator's aggregate), `verdict` (`FAKE` | `REAL` — the detector's individual
verdict before aggregation), `gradcamUrls` (array of ready-to-fetch artifact endpoint
paths — what clients should use), `gradcamKeys` (the raw object keys in
`analysis-artifacts`, kept for audit) and `metadata` (detector-defined free-form
object, snake_case keys; see [`amqp-messages.md`](./amqp-messages.md) for the audio
fields and the segment cap).

## Realtime progress (SSE)

### `GET /api/analysis/{id}/stream`

Server-Sent Events stream of progress + final result for one analysis (replaces the
earlier, unimplemented STOMP topic).

```
Accept: text/event-stream
Authorization: Bearer <token>
```

`200 OK` (`text/event-stream`) for the owner. `404 Not Found` at open time if the
analysis does not exist OR `userId != jwt.sub` (IDOR guard — never `403`). This open
check is repeated after registration; both reads are scoped to the owner, and events are then
pushed by `analysisId`. Exhausted SSE connection admission returns `429` with
`Retry-After: 5`. Defaults are128 global,4 per owner and4 per analysis; retained
physical connections count until their writer and HTTP request both finish.
Both open-time `404` and `429` have empty bodies even with `Accept: text/event-stream`;
normal missing/foreign subscriptions do not log WARN. Async disconnect/timeout does
not write an error body or log ERROR.

Events:

```
event: progress
data: {"analysisId":"uuid","source":"video","progress":50,"stage":"INFERENCE","status":"PROCESSING"}

event: result
data: {"analysisId":"uuid","status":"COMPLETED","verdict":"FAKE","confidence":0.74}
```

- `progress` coalesces to the latest pending update per detector source; `source` is `video` | `audio`.
- `result` carries the terminal state (`COMPLETED` | `FAILED` | `CANCELLED`; `verdict`
  and `confidence` are `null` unless `COMPLETED`); the server closes the stream right
  after sending it. Opening a stream for an already-finished analysis queues its own `result`
  for delivery after MVC initialization, then closes. Slow connections can instead close
  without terminal delivery and use the GET reconciliation below.
- Comment lines (`: ...`) are sent ~every 15 s as a heartbeat to keep idle connections
  alive through proxies; clients ignore them.

**Subscription consistency:** `openStream` suspends any caller transaction. It reads an
owner-scoped scalar snapshot in a separate `REQUIRES_NEW`, `READ_COMMITTED` transaction,
registers the emitter, then repeats that committed read. The scalar projection bypasses
JPA's managed entity identity map; neither a caller's stale snapshot nor its uncommitted
terminal change can become a replay. Completion before registration is recovered by the
second read; completion afterwards reaches the registered emitter through the existing
after-commit publication. Replay targets only the new emitter. Broadcast terminal delivery
atomically detaches the current subscribers before sending and closing them, so it cannot
close a later subscriber without a result. Concurrent push and replay can race; a completed
mailbox accepts only one terminal event. Failed reads and disconnected emitters are removed.
Delivery uses independent bounded per-connection writers and fixed mailboxes. A dedicated
heartbeat/watchdog scheduler never writes HTTP responses. Routing removal or timeout alone
does not recycle capacity while a physical writer/request remains blocked. See
[SSE delivery](../reliability.md#sse-delivery).

**Client recovery:** both upload and result screens use `watchAnalysis`, a fetch-based SSE
client with a Bearer header. On EOF or a transient stream error without `result`, it immediately
performs a GET catch-up. A result event also stops SSE and loads the complete terminal resource
(the event omits details and errorMessage). If GET is still active or transiently fails, polling
uses delays of 0, 500, 1000, 2000, 4000, 8000, 16000, 30000 and 30000 ms: exactly nine reads,
with no automatic SSE reconnect. Each complete GET operation (including token refresh before fetch) has a 10-second timeout. Browser HTTP caching is
disabled for analysis GETs; active responses from the backend's 60-second Redis cache remain
retryable. The 91.5-second backoff window extends beyond that TTL. The result screen uses the
recovered full resource directly, avoiding an additional potentially stale GET.

The caller's AbortSignal stops the stream, pending reads and backoff and suppresses late
callbacks. HTTP 4xx other than 429 stop immediately (including missing/foreign analyses).
Exhaustion presents a recoverable connection error on both screens instead of leaving the
interface indefinitely running; it does not cancel the backend analysis. A manual page refresh
starts a new subscription. Native `EventSource` cannot send Authorization headers, and a token
in a query string would leak into logs.

**Regression coverage:** `AnalysisStreamCompletionIntegrationTest` runs automatically in the
orchestrator Maven suite against disposable PostgreSQL, with real transaction proxies and
commit/rollback boundaries. It forces completion before, between and after subscription reads,
retains a stale managed entity inside a repeatable-read caller, checks two simultaneous and one
late subscriber, and rejects a foreign owner. Registry tests cover emitter-specific replay,
registration during a broadcast and failed-delivery cleanup. Vitest covers the SSE transport,
both screen hooks, EOF/errors, stale active reads, backoff, nine-attempt exhaustion and abort.
These suites run in the existing CI Java and frontend jobs.

## HTTP status codes

| Code | Meaning                                                    |
|------|------------------------------------------------------------|
| 200  | OK                                                         |
| 201  | Created                                                    |
| 400  | Bad Request (validation `VALIDATION_FAILED`, or malformed body `MALFORMED_REQUEST`) |
| 405  | Method Not Allowed (`METHOD_NOT_ALLOWED`, `Allow` header) |
| 406  | Not Acceptable (empty body)                                |
| 415  | Unsupported Media Type (`UNSUPPORTED_MEDIA_TYPE`)          |
| 401  | Unauthorized (missing/invalid token)                       |
| 404  | Not Found (also IDOR — we never return 403)                |
| 409  | Conflict (e.g. cancel on an already-finished analysis)     |
| 413  | Payload Too Large (file exceeds limit)                     |
| 422  | Unprocessable Entity (e.g. invalid file format)            |
| 429  | Too Many Requests (rate limit / queue backpressure)        |
| 500  | Internal Server Error                                      |
