# Media pipeline recovery harness (P4)

Disposable Docker Compose stack plus three integration scenarios that prove the processing path
recovers from the high-value failure modes without a full crash matrix.

The harness owns only the resources it creates: it runs under the explicit project name
`media-recovery-test`, publishes its own host ports (`18080` backend, `19000` MinIO, `5673`
RabbitMQ) and discards everything with `down -v`.

## Prerequisites

- Docker (the Compose file builds the backend and transcoder images).
- `ffmpeg` on PATH (used only to synthesise the source video for the worker-death scenario).
- Python 3 with `requests`, `boto3` and `pika`:

  ```powershell
  python -m pip install requests boto3 pika
  ```

## Run

From the repository root, each scenario can be run alone while developing:

```powershell
python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_worker_dies_after_claim -v
python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_duplicate_and_late_results -v
python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_upload_completion_response_loss -v
```

Then run the file once after all three pass:

```powershell
python tests/media-pipeline/test_recovery.py -v
```

The first invocation builds the backend and transcoder images (a few minutes); later invocations
reuse the Docker layer cache.

## Scenarios

- `test_worker_dies_after_claim` — a worker durably claims a job and is hard-killed; the backend
  expires the lease, re-queues the job after the retry delay and a restarted worker reaches READY
  on attempt two.
- `test_duplicate_and_late_results` — publishes a duplicate result event id, a lower-sequence
  progress event and a post-terminal progress event; the inbox dedupe and sequence/attempt fencing
  leave exactly one accepted terminal transition.
- `test_upload_completion_response_loss` — completes the multipart upload at storage while the
  session is left COMPLETING (the crash window after storage completion), then repeats the
  completion request; exactly one job and one outbox command exist.

## Teardown

`tearDownClass` runs `docker compose -p media-recovery-test -f tests/media-pipeline/compose.test.yml
down -v`. To inspect a stack manually, bring it up yourself and pass `-v` to see the same evidence.

## Protected playback smoke (P5)

`test_playback.py` drives the whole publication/playback slice against `compose.p5.yml` and proves
that the **production browser client** decodes protected HLS:

```powershell
python tests/media-pipeline/test_playback.py -v
```

It builds the backend, transcoder and frontend images, creates a managed movie, uploads a synthetic
video and two artwork files through the private multipart path, waits for the worker to produce READY
output, publishes the movie (which promotes only validated artwork), starts the media gateway, logs
in through the running web app so the browser owns the refresh cookie, and then loads
`/watch/{movieId}` in headless Chrome. The assertions are about what a real viewer's browser does:

- the Nginx `Content-Security-Policy` names the configured media origin in `connect-src`;
- the built bundle embeds the same media origin and resolves the backend's root-relative HLS path
  onto it;
- the app's own HLS.js transport sends the media bearer header to the gateway for the manifest and
  every segment, and the gateway (which answers 200 only for a verified, path-bound token) serves
  them;
- the player reaches `readyState >= 3` with a decoded 320x180 frame and advancing `currentTime`;
- no media or access token is written to browser storage;
- the same canonical manifest fetched without a token is refused with `401` before any storage read.

The gateway runs on the host at `http://127.0.0.1:8899` and the web app is published at
`http://127.0.0.1:18081`, so the media origin is genuinely cross-origin from the page - which is the
configuration the CSP and the bundle must both name. `media-gateway/p5-gateway-node.mjs` bundles
`media-gateway/src/index.ts` with esbuild and serves it from a Node HTTP server with an R2-compatible
delivery bucket backed by the Compose MinIO delivery bucket, plus a smoke-only request journal
(`GET /__smoke/requests`) that records the method, path, whether an `Authorization` header was
present and the status - never a token. Every authorization and delivery decision therefore comes
from the production gateway code; only the runtime environment differs from Cloudflare. Wrangler's
local R2 store is deliberately not used, because it is a second, empty bucket.

Prerequisites beyond the P4 harness: `websocket-client`, `cryptography` and `Pillow`
(`python -m pip install websocket-client cryptography Pillow`), Node with the `media-gateway`
dependencies installed, and a Chrome or Edge binary. The stack uses its own project name
`media-playback-test` and its own host ports; `P5_SKIP_BUILD=1` reuses an already-running stack, and
`tearDownClass` always runs `down -v`.
