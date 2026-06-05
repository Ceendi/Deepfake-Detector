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

When the per-user rate limit or backpressure threshold is exceeded, the
gateway / orchestrator returns `429 Too Many Requests` with body
`{ "queuePosition": <int> | null, "retryAfterSeconds": <int> }` and a
`Retry-After` header.

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
| `status`       | `PENDING` \| `PROCESSING` \| `COMPLETED` \| `FAILED`           | never                                          |
| `verdict`      | `FAKE` \| `REAL` \| `null`                                     | `status != COMPLETED`                          |
| `confidence`   | `number` (0..1) \| `null`                                      | `status != COMPLETED`                          |
| `videoProb`    | `number` (0..1) \| `null`                                      | source not analyzed                            |
| `audioProb`    | `number` (0..1) \| `null`                                      | source not analyzed                            |
| `details`      | `object` \| `null`                                             | MVP; later: Grad-CAM URLs and metadata         |
| `errorMessage` | `string` \| `null`                                             | `status != FAILED`                             |

## WebSocket (STOMP)

Endpoint: `ws://<gateway>/ws`. Subscription topic: `/topic/analysis/{id}`.

Progress message:

```json
{
  "analysisId": "uuid",
  "source": "video",
  "progress": 50,
  "stage": "INFERENCE",
  "status": "PROCESSING"
}
```

Final result message:

```json
{
  "analysisId": "uuid",
  "status": "COMPLETED",
  "verdict": "FAKE",
  "confidence": 0.74
}
```

## HTTP status codes

| Code | Meaning                                                    |
|------|------------------------------------------------------------|
| 200  | OK                                                         |
| 201  | Created                                                    |
| 400  | Bad Request (validation)                                   |
| 401  | Unauthorized (missing/invalid token)                       |
| 404  | Not Found (also IDOR — we never return 403)                |
| 413  | Payload Too Large (file exceeds limit)                     |
| 422  | Unprocessable Entity (e.g. invalid file format)            |
| 429  | Too Many Requests (rate limit / queue backpressure)        |
| 500  | Internal Server Error                                      |
