# Local acceptance

This runbook validates repository code and a disposable local stack. It does not contact the production application, SSH/Tailscale host, or Cloudflare APIs, and it does not prove browser decoding, CDN behavior, host capacity, deployment/rollback, or alert delivery.

## Prerequisites

- Python 3.10+
- Docker Engine/Desktop with Compose v2 using the local `default` context
- Java 21 and Maven 3.9+ on the host for Docker-discoverable Testcontainers
- Node.js 22.22.2 and npm
- Terraform 1.16.1

Run commands from the repository root unless a block changes directory. Terraform initialization may download the locked provider if it is not cached; it uses no backend and none of these commands plans or applies Cloudflare changes. The host-operations validators may pull their pinned public images but do not use production credentials or endpoints.

## API and load acceptance

Preview the acceptance workflow without starting a process or creating Docker resources:

```sh
python scripts/run_acceptance.py
```

For a short feedback run, explicitly create a disposable local stack:

```sh
python scripts/run_acceptance.py --apply --quick
```

Quick mode runs the HTTP end-to-end scenario and a 2-VU, 15-second load profile. It is useful feedback but is not the load acceptance criterion.

Run the complete local acceptance profile with:

```sh
python scripts/run_acceptance.py --apply
```

The runner builds backend and frontend images from the current workspace, creates a uniquely named `task8-acceptance-<id>` Compose project with fresh volumes and internal-only networks, publishes no host ports, then removes only that project and its volumes in `finally`. k6 receives no Docker socket, remote target, production environment, or production credentials.

The API scenario checks registration, login, refresh rotation and reuse rejection, logout, authentication, catalog/search/details, Problem Details and request IDs, watchlist idempotency and user isolation, progress ordering/completion/resume, and all three local HLS playlists and one-byte range responses. These media checks validate local HTTP transport, not browser playback or Cloudflare CDN acceptance.

The full load profile uses 20 constant VUs for 10 minutes. It measures API catalog, search, movie, watchlist, playback, and progress traffic through local Nginx, excluding media transfer and authentication setup. Setup creates one user at a time with seven-second pacing and bounded 429 retries so the production-equivalent 10-attempts-per-action/IP/minute limiter stays enabled. Acceptance requires API errors below 1% and GET p95 below 500 ms. Results characterize this machine, container limits, and fixture data; they are not a production capacity claim.

Sanitized aggregate k6 summaries are written to ignored paths:

```text
artifacts/task8/task8-acceptance-<id>/api.json
artifacts/task8/task8-acceptance-<id>/load.json
```

The summaries contain aggregate metrics and check names, not response bodies, tokens, cookies, usernames, or passwords. Preserve a copy outside the ignored directory only when evidence retention is required, and review it before sharing.

## Complete repository verification

Run the Python tool and safety tests:

```sh
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
python scripts/verify_task6.py
```

Run backend tests from a host Java 21/Maven process so Testcontainers can discover the local Docker daemon:

```sh
cd backend
mvn --batch-mode --no-transfer-progress test
cd ..
```

Tests annotated `disabledWithoutDocker` skip when Docker is unavailable; report their skip count rather than treating the run as PostgreSQL integration evidence. The 13 catalog external-integration cases and one Redis-fallback external case require the opt-in `catalog.external-it` system property and remain skipped in the normal Maven suite.

Run frontend tests, build/type checking, generated OpenAPI client drift checking, and dependency audit:

```sh
cd frontend
npm ci
npm run check
npm run check:api
npm audit --audit-level=high
cd ..
```

Validate Cloudflare configuration without a backend, plan, apply, credentials, or API call:

```sh
terraform -chdir=infra/cloudflare fmt -check
terraform -chdir=infra/cloudflare init -backend=false -input=false
terraform -chdir=infra/cloudflare validate
```

Validate local and production Compose rendering and the portable host-operations bundle, then run its disposable integration smoke:

```sh
docker compose -f compose.yml -f compose.local.yml config --quiet
docker compose -f compose.yml -f compose.prod.yml config --quiet
python infra/prod-host-ops/scripts/validate.py
docker build -f backend/Dockerfile -t media-streaming-backend:task7 .
docker build -f frontend/Dockerfile -t media-streaming-frontend:local .
python infra/prod-host-ops/scripts/smoke.py
```

Lint repository workflows with the same pinned actionlint version used by CI:

```sh
docker run --rm -v "$PWD:/repo" --workdir /repo rhysd/actionlint:1.7.12 -color
```

CI additionally runs `npm audit`, a Trivy filesystem scan with vulnerability, secret, and misconfiguration scanners, and HIGH/CRITICAL Trivy scans of both built images. The repository Trivy configuration uses offline scanning and skips generated/build/toolchain directories; results depend on the available local vulnerability database. Task 7's local Trivy evidence used embedded secret/misconfiguration checks and did not constitute an image vulnerability scan or Trivy coverage of Compose invariants. Do not translate a narrower local scan into a blanket security-pass claim.

Finally, run the full acceptance command from the previous section. Record the machine/OS, tool versions, exact command exits, Maven skips, k6 thresholds, and report paths. Do not mark live CDN or private-host checks complete from these local results.

## Pending live acceptance

- Cloudflare cache rule authorization and apply, immutable media upload, hash/range/CORS verification, and repeated-request `CF-Cache-Status: HIT`
- Linux host ownership and bounded-filesystem checks, existing-service capacity review, merged Tunnel and Tailscale policy review
- Actual private-host deployment and image rollback, public SPA/API routing, and alert firing/recovery through an external receiver
- Manual browser HLS decoding and playback behavior

See the [media runbook](cloudflare-r2-media.md) and [private-host operations template](../../infra/prod-host-ops/README.md) before performing separately authorized live work.
