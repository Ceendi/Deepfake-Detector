# Audio score contract (PR04)

## Decision scale and effective thresholds

The audio worker publishes `metadata.score_contract = "audio-threshold-v1"`
and `model_version = "v1.3.0-fast"` or `"v1.3.0-accurate"`. This version changes
score semantics, not model weights or checkpoints. Fast uses MelCNN with default
`MEL_EER_THRESHOLD=0.3049`; accurate uses Wav2Vec2 ONNX with default
`W2V2_EER_THRESHOLD=0.3000`. Environment overrides remain supported and the
effective threshold is recorded in every result. Thresholds must be finite and
strictly between 0 and 1; invalid scores/configuration fail processing rather than
produce an inconsistent result.

For raw score `s` and effective model threshold `t`, define:

```text
f(s,t) = s / (2*t)                         when s <= t
         0.5 + (s-t) / (2*(1-t))           when s > t
```

This continuous, strictly increasing function maps `0 -> 0`, `t -> 0.5`, `1 -> 1`.
It aligns decision thresholds; it is **not statistical calibration**, a measured
likelihood, or evidence of improved model accuracy. The existing model decision
is `FAKE` only when `s > t`; equality remains `REAL`.

The wire and database use four decimal places. Publish `round(f(s,t), 4)` for
`s <= t`; for `s > t`, publish `max(0.5001, round(f(s,t), 4))`. The published map
is nondecreasing, with plateaus from quantization. The positive-side guard prevents
an arbitrarily small positive margin from rounding to `0.5000` and reversing the
model decision. Consumers apply `prob_fake > 0.5` to the **published** value.
Raw `0.4` becomes `0.5684` (fast) or `0.5714` (accurate): both are `FAKE`, including
AUDIO aggregation and persistence.

## Result fields, pooling, segments and insights

- `prob_fake`: published score on the shared decision scale in `[0,1]`, despite
  the retained probability-like field name. The Orchestrator stores it unchanged
  in `audio_prob` and returns it as `audioProb`.
- `verdict`: `FAKE` iff published `prob_fake > 0.5`, otherwise `REAL`.
- `confidence`: `round(2 * abs(prob_fake - 0.5), 4)`, computed from the published
  score. Zero at the boundary, one at either endpoint; a decision margin, not
  an empirical chance that the verdict is correct. AUDIO source and aggregate
  confidence agree. FULL keeps `0.6*videoProb + 0.4*audioProb`, its strict `>0.5`
  decision, and `2*abs(aggregate-0.5)` confidence stored at four decimal places
  by PostgreSQL. FULL can differ from either source when evidence conflicts.
- `metadata.raw_prob_fake`: unrounded mean of the highest
  `max(1, floor(number_of_speech_windows * 0.3))` raw window scores. Pool raw
  scores before mapping; the mapped segment mean is not the overall score.
  Previous workers rounded windows before pooling; v1.3 avoids losing the
  raw decision margin during that intermediate rounding. The pooling rule and
  FULL weights are unchanged.
- `metadata.threshold_used`, `mode_used`: effective model threshold and mode.
- `metadata.segment_predictions`: overlapping one-second windows every 0.5 s,
  excluding VAD silence. Each entry retains `start_time`, `end_time`, unrounded
  `raw_prob_fake`, and mapped four-decimal `prob_fake`. Padded short recordings
  can have a final window ending after the original duration. Grad-CAM still
  uses the window with the highest raw score and the existing MelCNN path.
  The Orchestrator may uniformly sample the timeline to at most 500 entries;
  it retains the per-window fields and marks `segment_predictions_downsampled`.
- `metadata.insights`: localized heuristic text derived from published scores,
  not additional inference, attribution proof, or a calibrated explanation.
  High/low overall scores use `>0.85` / `<0.15`; suspicious windows use strict
  `>0.5`. The longest cluster joins starts at most 0.6 s apart; three or more
  windows yield a cluster description. Otherwise up to three suspicious windows
  with overall score `<=0.5` yield a local artifact description. The fallback
  states ambiguity. With no speech windows, retain the neutral raw score `t`,
  published `0.5`, `REAL`, zero confidence and an explicit no-segment-data insight.
  This fallback supplies no evidence of authenticity.

The frontend compares only versioned shared scores to `0.5`. It uses the same
strict decision at equality and distance-based confidence. Timeline colors use
mapped segment scores; their percentages describe the decision scale, not a
calibrated probability.

## Historical records and version compatibility

There is no database migration, backfill, or silent recalculation of history.
Existing `audio_prob`, aggregate verdict/confidence and JSON details stay exactly
as recorded. Missing `score_contract` denotes the legacy raw scale. The new UI
labels audio as archival, uses the recorded source verdict when present, and
omits the new-scale audio timeline. If the historical source verdict is missing,
it shows no inferred source verdict and emits no audio classification finding.
Historical top-level verdicts (including earlier inconsistencies) remain visible
as recorded. Run a new analysis to obtain a result under the new contract.

The Java consumer at main `ec010c679e18dccf029fdb7615d54219bca74481` already persists
free-form metadata, source verdict/confidence and four-decimal probabilities.
Its production behavior needs no change for v1.3; PR04 adds a real RabbitMQ /
PostgreSQL regression proving both AUDIO and FULL paths. An unversioned legacy
message is still accepted with legacy semantics: the consumer does not guess a
model threshold, normalize in Java, or upgrade metadata. Therefore **do not mix
old and new workers in one active deployment**. Drain old work first. An older UI
that treats raw audio as `P(FAKE)` or uses `>=0.5` is semantically incompatible
with this contract; deploy the PR04 UI before enabling the v1.3 producer.

## Controlled deployment and rollback

Perform these steps in a maintenance window. The commands below are deployment
instructions, not tests executed by PR04. Explicitly select the intended existing
project; never inherit `COMPOSE_PROJECT_NAME`, purge queues, delete volumes, or
replay historical results.

```sh
DEPLOYMENT_PROJECT='your-explicit-existing-project-name'
# From the matching deployment checkout/configuration:
docker compose -p "$DEPLOYMENT_PROJECT" --profile core stop gateway
```

1. Block all alternative analysis-creation entry points as well. Keep the old
   workers, Orchestrator, RabbitMQ, PostgreSQL and Redis running until accepted
   work completes. Do not replace workers while they have unacknowledged tasks.
2. Inspect ready **and** unacknowledged counts repeatedly until all analysis
   queues (`analysis.audio`, `analysis.video`, `analysis.results`,
   `analysis.progress`, plus all three analysis DLQs) are empty. Wait for every
   database row to become terminal; a partial FULL row must not bridge versions.

```sh
docker compose -p "$DEPLOYMENT_PROJECT" --profile core exec -T rabbitmq \
  rabbitmqctl list_queues name messages_ready messages_unacknowledged
docker compose -p "$DEPLOYMENT_PROJECT" --profile core exec -T postgres \
  sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT id,type,status FROM analysis WHERE status IN ('PENDING','PROCESSING');
SQL
```

3. Require zero rows and zero counts in consecutive observations, after all
   producers have stopped creating work. If a row or DLQ does not drain, stop
   deployment and diagnose it under the old version; do not manufacture an
   empty queue by purge or replay. Existing worker recovery/idempotence behavior
   is outside PR04.
4. Stop old workers only after the drain. Deploy the PR04 frontend, retain the
   compatible Java consumer (or deploy its tested PR04 build), then replace the
   audio worker with v1.3. Preserve all thresholds, models, checkpoints and data.
   Use the same explicitly selected project for every operation:

```sh
docker compose -p "$DEPLOYMENT_PROJECT" --profile ml stop audio-detector video-detector
# After deploying the frontend separately and building the intended worker image:
docker compose -p "$DEPLOYMENT_PROJECT" --profile ml up -d --no-deps audio-detector video-detector
docker compose -p "$DEPLOYMENT_PROJECT" --profile core up -d --no-deps gateway
```

5. Resume admission only when consumers are healthy. Submit new fast and accurate
   AUDIO analyses and a FULL analysis; inspect `score_contract`, raw score,
   effective threshold, stored score and source/aggregate verdicts.

Rollback follows the same admission pause and complete drain, this time letting
v1.3 finish its accepted work before reinstalling old workers. Keep the PR04 UI
(it understands both generations). Preserve v1.3 historical records and their
marker. Never feed mixed generations into an already-partial FULL analysis.

## Verification and limits

`audio-detector/tests/test_score_contract.py` checks normalization boundaries,
monotonicity, confidence, pooling, insight thresholds, both model-selection paths
with synthetic outputs, and actual consumer publication. Shared JSON fixtures in
`orchestrator/src/test/resources/contracts/audio-score-results.json` cross the
production AMQP listener and transactional service into PostgreSQL, then pass
through `AnalysisResponse` mapping for AUDIO and FULL, including scores adjacent
to the model thresholds. Frontend tests consume the same fixtures and verify
historical handling and threshold equality. The implementation was verified locally with 62 lightweight Python tests,
Ruff 0.16.10 for both detectors, `./mvnw verify` on JDK 25 (218 tests, including
32 real RabbitMQ/PostgreSQL contract cases; zero skipped), and the frontend test,
lint and build commands (127 tests). The six synthetic `analyze` wiring cases
fail against `origin/main` production inference and pass with PR04; raw `0.4`
reproduces the old contradiction in each mode. Local frontend lint was run on
the source tree before scanning generated Keycloak resources (generated assets
were preserved); CI already orders lint before tests and build.

Lightweight CI installs only
`tests/requirements-light.txt`; it does not download model checkpoints or ML
libraries. These tests do not establish full real-model inference.

The repository and original checkout contain only checkpoint placeholder files;
real MelCNN and Wav2Vec2 inference was **not run** for PR04. Deployment/drain and a
full end-user stack smoke were not exercised against the user's stack. Integration
checks use disposable Testcontainers resources with random host ports and a
dedicated network for the PR04 contract test; no Compose mutation is used.

Independent review in a fresh context confirmed one numerical edge case for a
valid subnormal threshold override. The implementation now divides before
multiplying on the lower branch, preventing intermediate underflow at equality;
new boundary/monotonicity and empty-speech regressions cover it. No other
actionable review findings remained.
