# REST API Contracts

Convention: **camelCase** for REST/JSON (Java/TS-friendly). The Orchestrator
maps snake_case (AMQP) → camelCase (REST) internally — AMQP payloads are
never forwarded to clients as-is.

Base path: `/api`. Auth: Bearer JWT (Keycloak OIDC, PKCE).

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
MP4/MOV/AVI/WAV/MP3/FLAC). `422` body uses code `INVALID_FILE`.

Response `200 OK`:

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "fileKey": "550e8400-e29b-41d4-a716-446655440000_test.mp4",
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
  "mimetype": "video/mp4"
}
```

`duration` is the media length in seconds (from `ffprobe`); `null` if unknown.
`name` is the original upload filename and may be `null`.

### `GET /api/files/{id}/presign`

Returns a short-lived (1 h) presigned URL for fetching the file directly from object
storage (e.g. `<video src=...>`). `200 OK`; `404 Not Found` for a missing, soft-deleted,
or non-owned file (IDOR — never `403`). The URL host is browser-reachable (not the
internal storage endpoint).

```json
{
  "url": "http://localhost:8333/deepfake-uploads/550e8400-..._test.mp4?X-Amz-Algorithm=...&X-Amz-Signature=...",
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

Start an analysis for an uploaded file.

```
Content-Type: application/json
Authorization: Bearer <token>
```

Body:

```json
{
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "fileKey": "550e8400-e29b-41d4-a716-446655440000_test.mp4",
  "type": "VIDEO"
}
```

`type`: `VIDEO` | `AUDIO` | `FULL`. Response `201 Created`: `Analysis` (see below).

Two distinct `429 Too Many Requests` can occur:

- **Orchestrator backpressure** (in-flight analysis limit): body
  `{ "queuePosition": <int>, "retryAfterSeconds": <int> }` plus a `Retry-After`
  header. `queuePosition` is the in-flight analysis count at the moment of rejection.
- **Gateway rate limit** (per-user token bucket): no JSON body; rate-limit state is
  carried in `X-RateLimit-*` headers (Spring Cloud Gateway default).

### `GET /api/analysis/{id}`

Returns `200 OK` with the full `Analysis` (see below). Returns `404 Not Found`
if the resource does not exist OR `userId != jwt.sub` (IDOR guard — never `403`).

### `GET /api/analysis`

Paginated history for the authenticated user. Query params: `page` (default `0`),
`size` (default `20`); ordered by `createdAt DESC`. Returns `200 OK` with a
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

### `DELETE /api/analysis/{id}`

Soft-cancels an in-progress analysis. `200 OK` with the `Analysis` (now
`status: CANCELLED`). Idempotent: cancelling an already-`CANCELLED` analysis also
returns `200`. `409 Conflict` (code `CONFLICT`) if the analysis already finished
(`COMPLETED`/`FAILED`). `404 Not Found` for a missing or non-owned analysis (IDOR —
never `403`). Any open SSE stream (below) receives a `result` event with
`status: CANCELLED` and is then closed.

## `Analysis` shape

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "keycloak-sub-uuid",
  "fileId": "550e8400-e29b-41d4-a716-446655440000",
  "fileKey": "550e8400-e29b-41d4-a716-446655440000_test.mp4",
  "type": "VIDEO",
  "status": "COMPLETED",
  "verdict": "FAKE",
  "confidence": 0.74,
  "videoProb": 0.87,
  "audioProb": null,
  "details": null,
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
| `details`      | `object` \| `null`                                             | MVP; later: Grad-CAM URLs and metadata         |
| `errorMessage` | `string` \| `null`                                             | `status != FAILED`                             |

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
check is the entire channel authorization; events are then pushed by `analysisId`.

Events:

```
event: progress
data: {"analysisId":"uuid","source":"video","progress":50,"stage":"INFERENCE","status":"PROCESSING"}

event: result
data: {"analysisId":"uuid","status":"COMPLETED","verdict":"FAKE","confidence":0.74}
```

- `progress` fires per detector update; `source` is `video` | `audio`.
- `result` carries the terminal state (`COMPLETED` | `FAILED` | `CANCELLED`; `verdict`
  and `confidence` are `null` unless `COMPLETED`); the server closes the stream right
  after sending it. Opening a stream for an already-finished analysis delivers `result`
  immediately, then closes.
- Comment lines (`: ...`) are sent ~every 15 s as a heartbeat to keep idle connections
  alive through proxies; clients ignore them.

**Client:** use a fetch-based SSE client (e.g. `@microsoft/fetch-event-source`) so the
`Authorization` header can be set — the native `EventSource` cannot send headers, and a
token in the query string would leak into logs.

## HTTP status codes

| Code | Meaning                                                    |
|------|------------------------------------------------------------|
| 200  | OK                                                         |
| 201  | Created                                                    |
| 400  | Bad Request (validation)                                   |
| 401  | Unauthorized (missing/invalid token)                       |
| 404  | Not Found (also IDOR — we never return 403)                |
| 409  | Conflict (e.g. cancel on an already-finished analysis)     |
| 413  | Payload Too Large (file exceeds limit)                     |
| 422  | Unprocessable Entity (e.g. invalid file format)            |
| 429  | Too Many Requests (rate limit / queue backpressure)        |
| 500  | Internal Server Error                                      |
