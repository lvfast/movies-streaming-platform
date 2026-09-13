# Local acceptance

This runbook verifies repository code and disposable local containers. Run commands from the repository root unless a command changes directory.

## Prerequisites

- Python 3.10+
- Docker Engine or Docker Desktop with Compose v2 and the local `default` context
- Java 21 and Maven 3.9+
- Node.js 22.22.2 or newer and npm

## API and load acceptance

Preview the workflow without starting processes, creating Docker resources, or writing reports:

```sh
python scripts/run_acceptance.py
```

Run the short API and 2-VU, 15-second load profile:

```sh
python scripts/run_acceptance.py --apply --quick
```

Run the complete API and 20-VU, 10-minute load profile:

```sh
python scripts/run_acceptance.py --apply
```

Each applied run builds the current backend and frontend, creates an isolated `local-acceptance-<id>` Compose project, and removes only that project and its volumes after the run. The project uses fresh volumes, internal-only networks, and no host ports.

The API scenario covers authentication and refresh reuse, catalog/search/details, Problem Details and request IDs, watchlist idempotency and user isolation, playback progress, and all three local HLS playlists with byte-range requests. The load scenario exercises API traffic through local Nginx; it excludes media transfer and authentication setup.

Sanitized aggregate reports are written beneath:

```text
artifacts/acceptance/local-acceptance-<id>/api.json
artifacts/acceptance/local-acceptance-<id>/load.json
```

These ignored files contain aggregate metrics and check names, not response bodies or credentials. Quick mode is feedback only. The complete profile requires API errors below 1% and GET p95 below 500 ms, but results describe the current local machine rather than general capacity.

## Repository and application checks

Run the offline Python tooling and repository-policy tests:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
```

Run backend tests:

```sh
cd backend
mvn --batch-mode --no-transfer-progress test
cd ..
```

Tests marked `disabledWithoutDocker` skip when Docker is unavailable. Record skipped Testcontainers cases separately instead of treating them as database integration evidence.

Run frontend tests, the build/type check, generated-client drift check, and dependency audit:

```sh
cd frontend
npm ci
npm test
npm run build
npm run check:api
npm audit --audit-level=high
cd ..
```

Render the local Compose model using the documented defaults:

```sh
docker compose --env-file .env.example -f compose.yml -f compose.local.yml config --quiet
```

Validate the Nginx configuration. The shipped `frontend/nginx.conf.template` is rendered at container
start by `frontend/docker-entrypoint.d/05-render-nginx-config.sh`, so reproduce that substitution
(only `MEDIA_ORIGIN`) and check the rendered file:

```sh
docker run --rm --add-host backend:127.0.0.1 -e MEDIA_ORIGIN=http://127.0.0.1:8899 \
  -v "$PWD/frontend/nginx.conf.template:/etc/nginx/templates/nginx.conf.template:ro" \
  -u nginx --entrypoint sh nginx:1.28-alpine -c \
  'envsubst "\${MEDIA_ORIGIN}" < /etc/nginx/templates/nginx.conf.template > /tmp/nginx.conf && nginx -t -c /tmp/nginx.conf'
```

Lint repository workflows with the pinned actionlint version used by CI:

```sh
docker run --rm -v "$PWD:/repo" --workdir /repo rhysd/actionlint:1.7.12 -color
```

## Limits of local acceptance

Local acceptance does not prove browser decoding, adaptive playback quality, accessibility, broad browser/device compatibility, external media delivery, deployment behavior, or host capacity. The load thresholds are bounded smoke criteria, not a service-level objective. Record the machine and tool versions, exact command exits, Maven skips, load thresholds, and report paths when retaining verification evidence.
