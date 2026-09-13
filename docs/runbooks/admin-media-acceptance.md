# Admin media acceptance runbook

This runbook drives the P8 integration acceptance journey: one complete, isolated end-to-end proof
of the admin media MVP across the real API, storage, RabbitMQ, worker, gateway and the Admin/player
UI. Run commands from the repository root.

## Prerequisites

- Python 3.10+ with `requests` and `boto3` installed
- Docker Engine or Docker Desktop with Compose v2 and the local `default` context
- Node.js (runs the real media gateway bundle locally)
- A Chromium-based browser with H.264 support: Google Chrome or Microsoft Edge, or Playwright's
  Chrome for Testing selected with `MEDIA_ACCEPTANCE_BROWSER_CHANNEL=chrome`
- Playwright tooling installed in `frontend/` (`npm.cmd --prefix frontend ci`)

## Run the journey

Preview the workflow without creating any resource or report:

```sh
python scripts/run_media_acceptance.py
```

Run the complete journey:

```sh
python scripts/run_media_acceptance.py --apply
```

An applied run:

1. Builds `backend`, `transcoder` and `frontend` from the current workspace.
2. Starts one disposable Compose project `media-acceptance-<10 hex>` with an internal application
   network and loopback-only web and storage ports, then creates the private buckets and configures
   upload CORS for exactly the journey's web origin.
3. Generates synthetic fixtures with FFmpeg, registers two synthetic accounts and grants ADMIN with
   the real operator command inside the backend container.
4. Starts the real media gateway bundle in Node against the acceptance delivery bucket.
5. Runs the nine-step Playwright journey in `frontend/e2e/admin-media-journey.spec.ts`: register and
   grant ADMIN, create a draft with USER denial and stale-ETag rejection, upload poster/backdrop/
   video with one paused-and-resumed multipart upload, observe RabbitMQ/worker READY output, preview
   without personal progress, attach artwork, publish, viewer playback through the gateway (401
   without token, 206 for an authorized range), replacement activation with the old session pinned,
   unpublish/archive/restore, and the audit trail with no Delete action.
6. Removes only that project (`down --volumes --remove-orphans`); nothing is pruned globally.

## Report

Each applied run writes a redacted report under:

```text
artifacts/media-acceptance/media-acceptance-<id>/report.json
```

The report contains schema version, project name, tool versions (docker, compose, node, playwright,
browser channel), image tags/digests, the journey summary with all nine named steps, durations,
setup details, the three provider checks (`NOT_RUN` with a reason) and known limitations. `redact`
runs over the whole report and `assert_redacted` refuses to write it: no credential, JWT, presigned
URL or raw source data can reach the report. `playwright.log` and the raw Playwright JSON for the
run are stored next to it for diagnosis.

The journey passes only when all nine steps pass through the actual services. The provider-only
R2/Cloudflare checks are always `NOT_RUN` here — missing cloud access can never become a false
pass and does not block local MVP completion; the staging commands live in
[admin-media-rollout.md](admin-media-rollout.md).

## Focused re-runs

A failing journey is diagnosed with its owning component test first. To re-run only one journey
step title while the harness supports it:

```sh
python scripts/run_media_acceptance.py --apply --grep "step 6"
```

`--grep` is diagnostic only; the complete journey must pass without a filter for release evidence.

## Repository checks

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
```

The harness tests cover dry-run safety, the disposable project namespace, Compose model isolation,
report redaction, cleanup behavior and the environment wiring (loopback storage endpoint, MinIO
upload CORS scope, gateway origin).

## Limits of local acceptance

The journey uses synthetic accounts and small legally generated fixtures in a disposable project;
it performs no provider deployment and deletes no retained media. workerd, the Cloudflare edge
cache, real R2 and the deployed routing are not exercised — those checks require the separately
authorized staging commands in [admin-media-rollout.md](admin-media-rollout.md). A local PASS
supports the local MVP completion decision, not production readiness on its own.
