# Object Storage Layout (SeaweedFS, S3-compatible)

> We use SeaweedFS (Apache-2.0, S3-compatible) — applications talk to it via
> the standard AWS S3 SDK, so the contract below is vendor-neutral.

## Buckets

| Bucket               | Contents                               | Object key                         |
| -------------------- | -------------------------------------- | ---------------------------------- |
| `deepfake-uploads`   | User-uploaded files                    | `{fileId}_{originalName}`          |
| `analysis-artifacts` | Grad-CAM PNGs and other visualizations | `{analysisId}/{source}/{name}.png` |

`{fileId}` and `{analysisId}` are full UUIDs (e.g.
`550e8400-e29b-41d4-a716-446655440000`). Do NOT shorten them in object keys —
collisions become non-zero past a few thousand uploads.

`{source}` ∈ `{video, audio}`.

Provisioned by [`infra/seaweedfs/init.sh`](../../infra/seaweedfs/init.sh) (config render)
and [`infra/seaweedfs/bucket-init.sh`](../../infra/seaweedfs/bucket-init.sh) (bucket create).

## Service credentials

| Service      | Env var key           | Env var secret           | Permissions                                                                          |
| ------------ | --------------------- | ------------------------ | ------------------------------------------------------------------------------------ |
| Admin (init) | `S3_ADMIN_KEY`        | `S3_ADMIN_SECRET`        | `Admin` — used only by `seaweedfs-bucket-init` to create buckets                     |
| File Service | `S3_FILE_SERVICE_KEY` | `S3_FILE_SERVICE_SECRET` | `Read/Write/List/Tagging` on `deepfake-uploads`                                      |
| Detectors    | `S3_DETECTOR_KEY`     | `S3_DETECTOR_SECRET`     | `Read/List` on `deepfake-uploads`; `Read/Write/List/Tagging` on `analysis-artifacts` |
| Orchestrator | `S3_ORCHESTRATOR_KEY` | `S3_ORCHESTRATOR_SECRET` | `Read/List` on `analysis-artifacts` only                                             |

Credentials are split by purpose: detectors must not be able to write user
uploads, the file service must not be able to overwrite ML artifacts, and the
orchestrator (which only serves Grad-CAM PNGs back to their owner) must not be
able to read user uploads or write anything. The admin identity exists only
for bootstrap and is never embedded in app config.

## S3 client configuration

| Service             | Endpoint                | Region      | Path-style |
| ------------------- | ----------------------- | ----------- | ---------- |
| Backend / detectors | `http://seaweedfs:8333` | `us-east-1` | required   |
| Host (dev tools)    | `http://localhost:8333` | `us-east-1` | required   |

SeaweedFS S3 requires **path-style addressing** (`endpoint/bucket/key`), not
virtual-hosted style (`bucket.endpoint/key`). Set
`pathStyleAccessEnabled=true` (Java) / `addressing_style="path"` (Python).

## Notes

- Real values live in `.env` (gitignored). `.env.example` ships placeholders.
- `analysis-artifacts` holds detector visualizations (audio Grad-CAM today, video
  top-frames next). Detectors publish the bare object keys in `result.gradcam_keys`
  (see [`amqp-messages.md`](./amqp-messages.md)); the Orchestrator persists them and
  serves the PNGs to the owner via `GET /api/analysis/{id}/artifacts/{source}/{name}`.
- Retention policy on `deepfake-uploads` is TBD (privacy requirements).
- Bucket creation is centralised in `seaweedfs-bucket-init` — apps must NOT
  call `CreateBucket` at startup (their identities lack `Admin` and would
  fail with 403).
- `file-service` and `detector` keys deliberately cannot call `ListBuckets`
  (the `aws s3 ls` no-arg form). They have `List:<bucket>` not global
  `Admin`, so listing the bucket _contents_ (`aws s3 ls s3://bucket/`)
  works but enumerating the bucket _list_ returns `AccessDenied`. Use the
  `admin` key when you need a bucket inventory.
