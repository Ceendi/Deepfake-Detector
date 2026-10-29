# Analysis Statuses

## States

| Status       | Meaning                                                                          |
|--------------|----------------------------------------------------------------------------------|
| `PENDING`    | Analysis created; task dispatched to detectors, none has started yet             |
| `PROCESSING` | A detector picked up the task (its start-ping arrived)                           |
| `COMPLETED`  | All required detectors returned a result; verdict computed                       |
| `FAILED`     | A detector returned an error, a message hit the DLQ, or stuck-job recovery fired |
| `CANCELLED`  | The owner cancelled the analysis before it finished                              |

`COMPLETED`, `FAILED`, and `CANCELLED` are terminal.

FULL has one explicit source exception: audio `NO_SPEECH` or `NO_AUDIO_TRACK` can be durably accepted
as `details.audio.status: "NOT_APPLICABLE"` with the matching reason and no audio
score. It still waits for successful video, then completes using video alone.
AUDIO `NO_SPEECH` / `NO_AUDIO_TRACK` and all other unaccepted required-source errors remain failures.
The first accepted audio score or explicit marker owns that source even after Redis
hint loss; later audio successes, failures and DLQs cannot replace it.

## Transitions

```
PENDING ──► PROCESSING ──► COMPLETED
   │             │
   ├─────────────┴──► FAILED
   │             │
   └─────────────┴──► CANCELLED
```

## Rules

- Orchestrator sets `PENDING` on creation.
- Orchestrator flips `PENDING` → `PROCESSING` on the first progress ping. Detectors send a
  start-ping (`progress: 0`, `stage: LOADING`) the moment they pick up the task, so
  `PROCESSING` shows from pickup. Every later progress bumps `updated_at` (heartbeat).
- The flip + heartbeat is one atomic conditional UPDATE
  (`SET status = 'PROCESSING', updated_at = now() WHERE id = :id AND status IN ('PENDING','PROCESSING')`),
  so a late progress on a terminal analysis matches nothing and is ignored.
- Aggregating video + audio results guards the last-write-wins race without `RETURNING`: each
  result writes only its own prob column (`SET video_prob = :p WHERE id = :id AND status IN
  ('PENDING','PROCESSING')` / analogously audio), so two concurrent disjoint-column writes both
  survive under the Postgres row-lock. Every terminal transition (complete/fail/cancel) is then a
  compare-and-set on the status (`SET status = :terminal WHERE id = :id AND status IN
  ('PENDING','PROCESSING')`); the row count picks a single winner, so database-counted capacity is freed and the SSE
  fires exactly once even when a result and a cancel race (week 6).
- [Stuck-job recovery](../reliability.md#stuck-job-recovery): a scheduled scan (every 300 s,
  first scan after 600 s) fails active analyses past any deadline: unsent dispatch 120 s,
  per-source pickup 1800 s from dispatch, per-source idle 600 s, or absolute age 7200 s
  from creation. Sources track `video_started_at`/`audio_started_at` and
  `video_progress_at`/`audio_progress_at` independently; sibling progress cannot extend
  their deadlines, and no progress extends absolute age. The terminal status CAS sets
  `FAILED`, frees capacity counted in PostgreSQL, and sends SSE after commit.
- DLQ consumer: a valid task or terminal result dead letter fails only an expected,
  unaccepted source of an active analysis. Accepted FULL sources and cancelled/terminal
  analyses are preserved; poison and permanent data failures are durably quarantined, while
  database outages requeue the dead letter and pause the listener until recovery.

## Paths to `FAILED`

1. Detector publishes a result message with `status: "FAILED"`.
2. A terminal result exhausts retries and is confirmed into `analysis.results.dlq`
   (or a detector task lands in `analysis.video.dlq` / `analysis.audio.dlq`); the DLQ
   consumer fails an active analysis only for an expected, unaccepted source. Progress
   has an independent advisory policy and never causes this transition.
3. [Stuck-job recovery](../reliability.md#stuck-job-recovery) fires while `PENDING`/`PROCESSING`:
   dispatch 120 s, per-source pickup 1800 s, per-source idle 600 s, or absolute age 7200 s.
   These are eligibility deadlines; failure happens on the next scan after a transactional recheck.
