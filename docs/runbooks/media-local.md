# Local media storage and processing runbook

P2 stores private uploads in an S3-compatible object store. P3 adds RabbitMQ and an independent
FFmpeg worker that converts a completed upload into validated HLS or artwork. The local stack uses
MinIO behind three named roles:

- `source` — internal server access from the backend and worker over the Docker application network.
- `source-browser` — presigns part URLs that a browser can reach directly from the host.
- `delivery` — validated HLS and artwork output written by the worker and verified by the backend.

Browsers receive presigned operations only; they never receive storage credentials.

## Start the stack

```powershell
docker compose --env-file .env.example -f compose.yml -f compose.local.yml -f compose.media.local.yml up -d --build
```

This adds MinIO on `127.0.0.1:9000` (S3 API) and `127.0.0.1:9001` (console), RabbitMQ on the
internal application network, and the transcoder worker. Media data persists under `./media-data`
(git-ignored).

## Create the buckets

The backend and worker do not create buckets. Create `media-source` and `media-delivery` once, either
from the console at <http://127.0.0.1:9001> (log in with `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`) or
with the MinIO client:

```powershell
docker run --rm --network media-streaming-platform_application `
  --entrypoint mc quay.io/minio/mc:latest `
  alias set local http://minio:9000 $env:MINIO_ROOT_USER $env:MINIO_ROOT_PASSWORD
docker run --rm --network media-streaming-platform_application `
  --entrypoint mc quay.io/minio/mc:latest mb --ignore-existing local/media-source
docker run --rm --network media-streaming-platform_application `
  --entrypoint mc quay.io/minio/mc:latest mb --ignore-existing local/media-delivery
```

## Configuration

The relevant environment variables default to the local MinIO/RabbitMQ values:

```text
MEDIA_SOURCE_BUCKET=media-source
MEDIA_DELIVERY_BUCKET=media-delivery
MEDIA_WORKER_CREDENTIAL=worker-local-credential  # shared machine credential (backend + worker)
WORKER_ID=worker-1
RABBITMQ_USERNAME / RABBITMQ_PASSWORD
S3_ENDPOINT=http://minio:9000          # backend/worker (internal) endpoint
S3_BROWSER_ENDPOINT=http://localhost:9000  # browser-reachable signing endpoint
S3_REGION=us-east-1
S3_ACCESS_KEY / S3_SECRET_KEY          # internal credentials
S3_BROWSER_ACCESS_KEY / S3_BROWSER_SECRET_KEY  # signing-only credentials (local: same)
MINIO_ROOT_USER / MINIO_ROOT_PASSWORD
```

For Cloudflare R2, set `S3_ENDPOINT` and `S3_BROWSER_ENDPOINT` to the R2 account endpoint,
`S3_REGION=auto`, and provide distinct access keys so the browser-signing role is signing-only.

## Happy-path smoke check

After an operator grants ADMIN and an admin uploads/completes a video, the outbox publisher delivers
the command, the worker claims and processes it, and the job becomes `SUCCEEDED` with the media
version `READY`. The admin can then read it through `GET /api/v1/admin/jobs/{jobId}` and
`GET /api/v1/admin/movies/{movieId}/versions`.

Two focused smoke scripts exercise the real worker against the running stack (they place a synthetic
source via `mc` over the internal network and queue the job/outbox rows directly, avoiding the need
for a host-reachable MinIO port):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/worker-smoke.ps1   # video -> READY HLS
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/artwork-smoke.ps1  # poster -> READY 600x900
```

The full HTTP upload journey is `scripts/happy-path.ps1` (register → grant ADMIN → upload → complete
→ outbox → RabbitMQ → worker → READY → decode the HLS). It requires the MinIO host port to be
published: MinIO must be attached to the `edge` network (not only the `internal: true` application
network), otherwise Docker Desktop does not publish its `ports:`. `S3_BROWSER_ENDPOINT` defaults to
`http://127.0.0.1:9000` for an unambiguous IPv4 host path. Automated coverage lives in
`ProcessingHappyPathTest`/`AdminMediaHttpTest`/`ArtifactVerifierTest`/`MediaContractSchemaTest`
(backend) and the transcoder's `VideoProbeTest`/`HlsEncoderTest`/`ArtworkProcessorTest`/
`CommandConsumerTest`/`DefaultJobExecutorTest`.

## Notes

- `docker compose config --quiet` renders the overlay; run it after changing compose files.
- Object keys are backend-owned (`source/{movieId}/{uploadId}/original`,
  `hls/{movieId}/{versionId}/{attemptId}/...`, `artwork/{movieId}/{assetId}/{attemptId}/...`); raw
  filenames never appear in keys, and no storage credential is ever returned to a browser.
- The worker has no JDBC/JPA or database driver dependency and never connects to the database.
- The MinIO image tag is `latest` for local development; pin a specific `RELEASE.*` tag for a
  reproducible environment.
