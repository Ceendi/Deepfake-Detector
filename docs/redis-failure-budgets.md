# Redis failure budgets

Redis supplies optional orchestrator cache, per-source deduplication, progress and cancellation hints.
PostgreSQL still controls admission, source acceptance and terminal state. No Redis capacity
reservation is used. Detector keys `cancel:{id}`, `progress:{id}` and `dedup:{id}:{source}` are unchanged.

The gateway treats Redis differently: its per-user limiter is protective. Redis failure returns
**503 Service Unavailable** before forwarding a protected route, while an exhausted bucket returns
**429 Too Many Requests**. Missing/invalid JWTs still return 401 through the existing security chain;
downstream services retain their ownership and role checks. The wrapper preserves Spring Cloud
Gateway's token-bucket script/configuration and converts its `remaining=-1`
failure sentinel into 503. The overall deadline also covers connection acquisition and script retry.
The integration test must run when upgrading Spring Cloud Gateway because this sentinel is an
implementation contract of the current 5.0.x limiter.

| Environment variable | Default | Scope |
| --- | --- | --- |
| `REDIS_CONNECT_TIMEOUT` | `250ms` | Gateway/orchestrator Lettuce socket connection |
| `REDIS_COMMAND_TIMEOUT` | `250ms` | Gateway/orchestrator Redis command |
| `REDIS_DEGRADED_BYPASS` | `2s` | Shared optional orchestrator operations after an actual Redis failure |
| `GATEWAY_REDIS_LIMITER_BUDGET` | `750ms` | Whole gateway limiter operation |

These duration values are bound through production `application.yaml` and passed through Compose.
The first failed optional operation incurs the client wait; subsequent optional operations briefly
return their fallback without touching Redis. Skipped calls do not extend the bypass. At expiry,
normal operations are tried again; another failure starts a new brief bypass. Concurrent calls that
started before the first failure may each incur one wait; this is a small availability bypass,
not a distributed circuit breaker or an admission mechanism.

Each process starts with a unique cache namespace, and each actual optional Redis failure replaces
it. This avoids reusing a pre-outage cache after eviction was skipped, including after restart or
repeated outages. Old namespace entries keep their existing 60-second TTL. Cache warm-up is lost on
restart/outage; cache ownership checks still run on every returned snapshot. This namespace policy
assumes the supported single orchestrator instance; multiple instances would require shared
invalidation before relying on this cache. Cancellation remains a
best-effort after-commit hint: during bypass a worker can finish and its late result is rejected by
the database's terminal guard.

## Fault acceptance

`RedisDownDegradationIntegrationTest` imports Boot's Redis auto-configuration and reads the production
YAML. It asserts the actual factory has 250ms connect/command defaults, warms that connection, then
pauses only its disposable Redis container (TCP stays established). Committed service transactions
continue against disposable PostgreSQL. GET including fallback/refill and result acceptance including
after-commit callbacks must each finish within 1.5 seconds, allowing database and test-host scheduling
margin around the 250ms Redis budget. Three immediate GETs during the bypass must finish within 500ms.
The test checks accepted source probability, duplicate rejection, admission/cancellation during the
outage, fresh COMPLETED cache recovery after unpause, restart namespace isolation, and healthy
progress/dedup/cancel keys. No timeout is shortened by the test.

`GatewayRateLimitIntegrationTest` similarly warms the production Boot client, pauses its disposable
Redis, and checks bounded HTTP 503, unauthenticated/invalid-token 401, and limiter recovery after
unpause. Its normal burst test verifies 429 remains separate. JWT decoding is stubbed; these are
Redis/security-chain integration checks, not a new Keycloak acceptance claim.

Run with Java 25 and Docker available. The existing worker-process integration tests also require
the lightweight Python dependencies from `audio-detector/tests/requirements-light.txt`; set
`DETECTOR_TEST_PYTHON` to that environment's Python executable when it differs from `python3`:

```sh
cd orchestrator && ./mvnw verify
cd ../gateway && ./mvnw verify
```

The tests use isolated Testcontainers and always unpause their Redis in `finally`. They do not reset
application volumes or flush developer Redis.
