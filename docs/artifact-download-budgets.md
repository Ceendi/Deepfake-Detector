# Artifact authorization and storage budgets

The PNG artifact endpoint retains its recorded-key membership and owner checks. A separate Spring
`ArtifactAuthorizationService` resolves only the owned recorded key inside a read-only transaction.
That service proxy finishes the transaction before `ArtifactService` contacts S3. The HTTP download
entrypoint runs without a transaction, so blocked artifact reads do not keep authorization database
connections checked out. No entity or lazy database state is needed during the remote read.

The S3 response is consumed by a managed `ResponseTransformer`, inside the SDK's call and attempt
timeout scope. Returning an unmanaged `ResponseInputStream` after headers would end that scope before
the body is consumed, so it is deliberately avoided. Continuously trickling bodies are stopped by the
operation deadline even when every socket read remains active.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `ARTIFACT_MAX_BYTES` | `8388608` (8 MiB) | Maximum accepted artifact body |
| `ARTIFACT_STORAGE_CALL_TIMEOUT` | `5s` | Whole S3 call including retries and body consumption |
| `ARTIFACT_STORAGE_ATTEMPT_TIMEOUT` | `2s` | One attempt including body consumption; also socket idle bound |
| `ARTIFACT_STORAGE_CONNECT_TIMEOUT` | `500ms` | Connection establishment and pool acquisition |

Production YAML and Compose expose these values. They apply only to the orchestrator's generated
artifact S3 client, including durable cleanup deletes and listing. File-service upload clients and
worker upload policies keep their own budgets. Oversized declared bodies are rejected before body
allocation; bodies without a trustworthy length are read through at most the byte limit plus one
byte. A successful read returns a bounded byte array. Memory use includes the bounded accumulation
buffer and returned array, not just the payload byte limit.

Successful bodies are fully consumed and closed. Oversize, truncated, failed or interrupted reads
abort the response before closing, avoiding the HTTP client's potentially large/slow drain of the
remaining body. Unknown analysis, foreign owner, unrecorded name and missing storage objects remain
404; storage timeouts, failed reads and oversized objects return the existing storage-unavailable
503 response. The endpoint path and PNG response type remain unchanged.

## Acceptance

`ArtifactDownloadIntegrationTest` uses real PostgreSQL, actual Spring service proxies and the
production `ObjectStorageConfig` against a local HTTP S3 fault fixture. It verifies:

- Three simultaneous blocked response bodies with a two-connection database pool, zero checked-out
  database connections after authorization, and unrelated database work finishing within 500ms.
- Missing/foreign/unrecorded requests, healthy content, delayed headers and a continuously trickling
  body. The production 5s total and 2s attempt settings are asserted on the actual client. Faulted
  calls finish within six seconds, including host scheduling margin, and subsequent reads recover.
- Oversized Content-Length and chunked bodies, followed by healthy reads using the same client.

`BoundedArtifactResponseTransformerTest` checks exact-limit success, at most limit+1 consumed bytes,
truncated/read-failure handling, and close/abort behavior. Existing `ArtifactServiceTest` keeps the
membership/owner, stored-key and 404/503 regressions.

Run `./mvnw verify` from `orchestrator/` with Java 25, Docker and the existing lightweight worker-test Python
runtime in `DETECTOR_TEST_PYTHON`. The PostgreSQL container and HTTP fixture are isolated and cleaned up
without touching developer services or volumes.

This PR is stacked on `fix/redis-failure-budget`; merge the Redis PR first. Durable deletion retry
and orphan cleanup are documented in [artifact-cleanup.md](artifact-cleanup.md).
