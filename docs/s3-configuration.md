# SeaweedFS S3 configuration rendering

The `seaweedfs-init` service renders `infra/seaweedfs/s3.json.tmpl` before
SeaweedFS starts. Its Python standard-library renderer parses the template as
JSON and assigns complete credential string values. It does not perform text
substitution or reprocess inserted values. Ampersands, pipes, backslashes,
quotes, spaces, dollar signs and Unicode remain exactly as supplied.

## Runtime and inputs

Compose pins the official multi-platform image
`python:3.14.8-alpine3.24` to the manifest digest
`sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72`.
This image supplies Python and its JSON library on both CI's amd64 platform and
local arm64 systems. No `apk`, `pip`, Python or jq installation runs at startup.
The renderer and template are read-only mounts. The service retains the existing
capability restrictions, resource limits and shared configuration volume.

All eight environment variables are required:

- `S3_ADMIN_KEY`, `S3_ADMIN_SECRET`
- `S3_FILE_SERVICE_KEY`, `S3_FILE_SERVICE_SECRET`
- `S3_ORCHESTRATOR_KEY`, `S3_ORCHESTRATOR_SECRET`
- `S3_DETECTOR_KEY`, `S3_DETECTOR_SECRET`

Values must be non-empty valid UTF-8 strings without ASCII control characters
(U+0000–U+001F or U+007F). Access keys must be distinct across the four identities.
Spaces are retained, including leading and trailing spaces. The renderer does
not impose AWS's alphanumeric access-key format: it preserves Unicode access
keys too. SeaweedFS 4.48 strips spaces from access keys when parsing SigV4 headers, so
use access keys without spaces for actual clients. The renderer still preserves
spaces exactly. The real S3 regression uses ASCII access keys containing special
characters and secrets containing spaces and Unicode; the image regression tests
spaces and Unicode in both key fields. See the [upstream SigV4 parser](https://github.com/seaweedfs/seaweedfs/blob/4.48/weed/s3api/auth_signature_v4.go#L125-L129).

Compose environment interpolation happens before the renderer runs. Supply
literal values using appropriately quoted environment variables or Compose's
supported `.env` quoting; do not rely on the renderer to undo interpolation.
Never place secret values in command arguments, diagnostic output or Git.

The template must contain the existing four named identities, their one
credential pair with the corresponding whole-value placeholders, and non-empty
string action lists. Duplicate JSON fields, invalid constants, unknown or
repeated identities, and invalid placeholders are rejected. The existing actions
are preserved: admin has `Admin`; file-service is scoped to `deepfake-uploads`;
orchestrator is scoped to `analysis-artifacts`; detector reads uploads and reads
and writes artifacts, with the existing list and tagging permissions unchanged.

## Publication and failure

The renderer validates inputs and template structure before writing anything.
It serializes to a unique private `0600` temporary file in `/out`, parses the
written JSON back and compares it with the intended document. It then sets the
existing downstream mode `0644`, flushes the file to storage with `fsync`, closes
it and atomically replaces `/out/s3.json` using `os.replace` on the same filesystem.
SeaweedFS mounts the containing directory, so subsequent starts see the new file.
Atomic replacement protects readers from partial JSON; it is not a promise of
power-loss recovery or coordinated credential rotation across running clients.

A validation, encoding, write or replacement error exits nonzero. Input errors
identify the variable or template problem without displaying its value; other
errors use a generic message rather than printing parser contents or OS exception
details. Temporary files are removed after ordinary failures. An abrupt process
kill can leave an unpublished temporary file (0600 while rendering, or 0644 after
validation), but does not replace the destination. An existing
valid configuration remains unchanged, and an empty volume gets no destination
file on failure. Compose's successful-init dependency prevents a new SeaweedFS
container from starting with a failed render. A running S3 process is not updated
by rerendering alone.

## Deployment and rollback

Deploy the Compose service change, `init.sh`, `render_config.py` and the template
together. Pull the pinned init image ahead of the maintenance window. Keep the
existing storage and configuration volumes. Verify all eight variables are
provided to init and match the consuming clients; retain a protected backup of
the previous valid configuration before changing credentials. Stop SeaweedFS,
run the new init successfully, and recreate/start SeaweedFS and bucket-init.
Recreate affected clients when their credentials change. Bucket-init uses real
signed admin requests and succeeds whether the required buckets are new or
already present. A plain restart of a container does not update its environment.
Use an explicit project name for all deployment operations and target the
intended stack; no volume deletion is required.

If init fails, correct the input/template/image issue and rerun it before starting
SeaweedFS. Do not bypass a failed init for an empty configuration volume. For a
rollback, restore a known valid renderer/image/template pair and matching client
environment, rerender successfully, then restart S3 and rerun bucket-init. If
returning to the pre-PR09 application/Compose version with credentials containing
special characters, retain the PR09 init service (image and both script mounts)
or the previously validated configuration and bypass the old init deliberately:
the old `sed` renderer cannot safely regenerate those credentials. Avoid reverting
only the image while leaving the Python script in place. Restore a protected
configuration backup atomically when rerendering is unavailable, and verify
signed requests with its matching credentials. Never delete storage volumes or
rotate secrets implicitly as a rollback step.

## Regression checks

Run `python3 infra/tests/test_s3_configuration.py` with Docker Compose available.
The harness resolves the production Compose configuration without printing it,
then creates random isolated projects, volumes, a network and a loopback port.
Every mutating Compose invocation, including cleanup, explicitly passes `-p`.
No developer `.env`, existing volumes or fixed host ports are used.

The tests execute `init.sh` and the renderer inside the exact pinned init image,
cover all required special characters and combinations in every credential,
missing/empty/invalid inputs, malformed templates, permission preservation and
failed validation/write/rename without overwriting a previous valid file.
A fresh real SeaweedFS stack exercises signed admin and client reads, writes,
listing and tagging in their allowed scopes, denies forbidden scopes and a wrong
secret, and checks successful bucket-init. It repeats these checks after
rerendering an existing configuration and restarting S3, and after a failed init
followed by an S3 restart using the retained file. These checks run in the
Infrastructure smoke workflow; changes under `infra/seaweedfs/**` trigger it.
