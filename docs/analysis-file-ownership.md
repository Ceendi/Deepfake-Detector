# Authorized analysis input (PR03)

## Contract and boundaries

`POST /api/analysis` requires `fileId` and `type`; audio `mode` stays optional.
The frontend sends the ID without a storage key. Old clients can still send
`fileKey` up to 500 characters, but the value is ignored, including mismatches.
Only the canonical `objectKey` returned by file-service is saved as analysis
`fileKey` and sent as AMQP `file_key`. No client key is used as a fallback.

The existing `GET /api/files/{id}/metadata` response gains the additive
`objectKey` field. Its existing active-row lookup and `userId == jwt.sub` check
remain authoritative. Missing, foreign and soft-deleted rows all produce `404`.
The orchestrator forwards the token from Spring Security's authenticated `Jwt`,
not an arbitrary request header or `X-User-ID`. It does not access the file-service
database or gain S3 upload permissions. JWTs are not stored or published.

`AnalysisCreationService` is a separate Spring bean with `Propagation.NEVER`:
calling it inside an existing transaction fails before HTTP. It validates the
request and resolves metadata before calling `AnalysisService.createResolved`
through the writer's proxy. That method opens the existing READ_COMMITTED
transaction, admits capacity, saves and publishes. Existing PR01 PostgreSQL
capacity semantics are retained. There is no self-invocation boundary.

`FILE_SERVICE_BASE_URL` defaults to `http://file-service:8081`. Local host runs
can override it to the actual file-service address. `FILE_SERVICE_LOOKUP_TIMEOUT`
defaults to `2s` and must be positive. The timeout covers connection, headers and
the entire response body via a bounded asynchronous wait. Redirects and retries
are disabled. Interrupted requests cancel the pending lookup and restore the
thread's interrupted status. A lookup never holds the writer transaction or its
admission lock while waiting for HTTP.

File-service `404` becomes analysis `404`. Other dependency statuses, network
failure, timeout, malformed metadata, a different file ID, or absent/invalid
`objectKey` become a generic controlled `503`. In particular, an older file-service
without the additive field fails closed. No analysis row, task or capacity
reservation is made for these lookup failures. Unauthenticated analysis requests
remain `401`; role checks remain unchanged.

## Rollout and deletion

1. Deploy the compatible file-service metadata extension first. Check that an
   owned active row returns its actual `objectKey` and foreign/deleted IDs return
   `404`. Existing metadata consumers continue to use their existing fields.
2. Deploy the orchestrator facade and HTTP client. Verify its internal URL,
   deadline and JWT issuer/JWK configuration. Old frontend requests still work
   because their legacy key is ignored. The old orchestrator remains vulnerable
   until this step; do not route analysis creation to it after rollout.
3. Deploy the frontend that sends only `fileId` and the analysis options.

There are no database migrations, new storage permissions, or required queue
changes. Do not roll file-service back to a response without `objectKey` while
this orchestrator runs: creation would safely return `503`. Rolling the
orchestrator back would reintroduce client-controlled input and is not a safe
security rollback.

Authorization is a snapshot at metadata resolution. A deletion racing between
that successful lookup and admission does not revoke the already resolved input;
no distributed file lock or second transaction across services is introduced.
A file deleted after acceptance blocks new analyses, but does not cancel or
rewrite accepted analyses or their queued keys. The existing file-service cleanup
retains a soft-deleted object for **72 hours** by default, measured from deletion
(`FILE_CLEANUP_RETENTION_HOURS`), then removes it on a later sweep. Detectors can
read the retained object during that window. A task delayed beyond retention may
fail when the object is purged; the existing detector failure/recovery behavior
applies. PR03 does not pin objects, extend retention or promise completion beyond
that window. Analysis records and reports keep their existing lifecycle.

## Regression and acceptance

- `AnalysisFileResolutionIntegrationTest` crosses real HTTP and PostgreSQL
  boundaries and inspects transaction state during lookup and admission. It
  verifies canonical row/task payloads, legacy-key ignoring, ID-only creation,
  failure statuses, malformed/old metadata, connection refusal, header and body
  deadlines, and rejection of an ambient transaction before lookup.
- Existing capacity/rollback and audio-mode tests exercise the renamed writer.
  Controller regression checks the authenticated JWT subject/token and a forged
  identity header. File-service tests check the additive metadata key. Frontend
  tests check the ID-only request and that `404`/`503` stop before streaming.
- `python3 infra/tests/test_analysis_file_ownership.py` starts real file-service,
  orchestrator, Keycloak, PostgreSQL, RabbitMQ, Redis and SeaweedFS. It uploads
  WAV files under two distinct signed-token accounts; file-service performs real
  JWT validation and database ownership checks. It exercises AUDIO/VIDEO/FULL,
  including own ID with the other account's legacy key, and reads the actual
  RabbitMQ tasks and committed canonical keys. Foreign/missing/deleted IDs,
  paused file-service (timeout), and stopped file-service (connection failure)
  must leave rows, occupied capacity and queued tasks unchanged. It also checks
  authorization before admission at full capacity and deletion after acceptance.

The Docker test has no workers, so it verifies admission and real publication,
not inference. It creates a unique project, explicitly uses `--project-name` on
**every** Compose operation, publishes unused loopback ports, and names its own
network and volumes. It reads `.env.example`, never the developer's `.env`.
Cleanup removes only the generated project and its volumes. Java Testcontainers
use disposable containers with generated ports and their own reaper; they do not
operate on the developer's Compose project.

## Verification record

Base: `origin/main` at `62992f7780e50fc26582c7ad929d210d0edc97d2`, refreshed on
2026-10-04. CI, Security scan and Infrastructure smoke all completed successfully.
PR01 (#85) and PR02 (#88) are already present; neither is implemented again.

Local verification on 2026-10-04:

- JDK 25 (`temurin-25.0.4`): `./mvnw -B -ntp verify` in orchestrator:
  **186 tests, 0 failures/errors/skips**, BUILD SUCCESS; file-service:
  **52 tests, 0 failures/errors/skips**, BUILD SUCCESS.
- Frontend: `npm ci`, `npm run test:run` (**108 tests**),
  `npm run lint -- --ignore-pattern 'public/keycloakify-dev-resources/**'`, and
  `npm run build` passed. Plain local lint found two missing-rule references in
  Keycloakify-generated vendor JavaScript; only that generated directory was
  excluded. No user or untracked files were deleted.
- `python3 infra/tests/test_analysis_file_ownership.py`: **PASS**, including real
  signed-token authorization, uploads and canonical RabbitMQ tasks for all three
  analysis types. Project `analysis-ownership-378cf57b8523` and its disposable
  resources were cleaned up; the test finished successfully in 181.9 seconds.
- `git diff --check` and Python smoke-test syntax compilation passed.
- Independent review by a fresh GPT-6.1 Sol reviewer (High), covering both HTTP
  ends, transaction/deadline boundaries, tests and deployment/delete docs:
  **no actionable findings**. The reviewer inspected the recorded test results
  instead of duplicating the running suites.

The GitHub PR records CI status for its final commit. Local tests above are not
presented as a substitute for that check.

Scope limitations: detector model/checkpoint inference, progress/result/report
end-to-end flow, worker restart, outbox, result idempotence and SSE recovery are
not part of PR03 acceptance and are not exercised by this new smoke test.
