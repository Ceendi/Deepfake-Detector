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
- Aggregating video + audio results guards the last-write-wins race with an atomic
  `UPDATE ... WHERE status IN ('PENDING','PROCESSING') RETURNING *` (week 6).
- Stuck-job recovery: a `@Scheduled` scan (every 5 min) fails analyses left
  `PENDING`/`PROCESSING` with no progress past the threshold (default 600s) — sets `FAILED`,
  releases the in-flight slot, and notifies the client over SSE.
- DLQ consumer: any message reaching a `*.dlq` flips the analysis to `FAILED` and notifies
  the client over SSE.

## Paths to `FAILED`

1. Detector publishes a result message with `status: "FAILED"`.
2. A result/progress message exhausts retries and is republished to `analysis.results.dlq`
   (or a detector task lands in `analysis.video.dlq` / `analysis.audio.dlq`); the DLQ
   consumer then fails the analysis.
3. Stuck-job recovery fires (no progress past the threshold while `PENDING`/`PROCESSING`).
