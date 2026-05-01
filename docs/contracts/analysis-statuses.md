# Analysis Statuses

## States

| Status       | Meaning                                                                                       |
|--------------|-----------------------------------------------------------------------------------------------|
| `PENDING`    | Analysis created; task not yet dispatched to detectors                                        |
| `PROCESSING` | At least one detector confirmed start (V2; in MVP analyses jump from `PENDING` to `COMPLETED`) |
| `COMPLETED`  | All required detectors returned a result; verdict computed                                    |
| `FAILED`     | A detector returned an error, the message went to DLQ, or stuck-job timeout fired (>10 min)   |

## Transitions

```
PENDING ──► PROCESSING ──► COMPLETED
   │              │
   └──────────────┴──► FAILED
```

In MVP (Walking Skeleton) the path is simply `PENDING → COMPLETED` or
`PENDING → FAILED`. The intermediate `PROCESSING` state is added in V2.

## Rules

- Orchestrator sets `PENDING` on creation.
- Orchestrator sets `PROCESSING` after the first progress event arrives (V2).
- Aggregating video + audio results uses an atomic
  `UPDATE ... WHERE status = 'PENDING' RETURNING *` to guard the race.
- Stuck-job recovery: a `@Scheduled` job runs every 5 min and marks
  analyses `> 10 min` in `PROCESSING` as `FAILED`.
- DLQ consumer: any message reaching DLQ flips the analysis to `FAILED` and
  notifies the client over WebSocket.

## Paths to `FAILED`

1. Detector publishes a result message with `status: "FAILED"`.
2. Message exhausts retries and lands in the DLQ.
3. Stuck-job recovery timeout fires (>10 min in `PROCESSING`).
