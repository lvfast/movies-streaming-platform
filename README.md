# LVFAST Media Streaming Platform

Portfolio-oriented movie streaming demo with a React frontend, a Java 21/Spring Boot backend, PostgreSQL, Redis, 20 seeded movies, and three local HLS fixtures. Guests can browse and search; registered users can manage a watchlist, play a fixture, and resume viewing progress.

## Features

- Browse home rails, search the catalog, and view movie details.
- Register, sign in, rotate refresh tokens, and sign out.
- Maintain a per-user watchlist and playback progress.
- Exercise HLS playlists and byte-range delivery with small local fixtures.
- Validate the API and a bounded load profile in a disposable local stack.

## Architecture

Nginx serves the React single-page application and local media, and proxies `/api/` to a Spring Boot modular monolith. PostgreSQL stores durable application data; Redis provides disposable catalog caching and authentication rate limiting. The checked-in [OpenAPI 3.1 contract](docs/api/openapi.yaml) defines the public API and generates the frontend client.

See [Architecture](docs/architecture.md) for module, data-flow, API, and security details.

## Prerequisites

For the containerized application, install Docker Engine or Docker Desktop with Docker Compose v2. Host-side development and verification additionally use Python 3.10+, Java 21 with Maven 3.9+, and Node.js 22.22.2 or newer with npm.

## Quick start

From the repository root, build and start the local stack:

```sh
docker compose --env-file .env.example -f compose.yml -f compose.local.yml up --build
```

Open <http://localhost:8080>. The local overlay publishes only the Nginx entrypoint on loopback and creates reusable development JWT keys in a named volume.

Stop the stack while retaining PostgreSQL data and local keys:

```sh
docker compose --env-file .env.example -f compose.yml -f compose.local.yml down
```

Add `--volumes` only when intentionally resetting local data and keys.

## Configuration

`.env.example` contains safe local defaults and is used directly by the commands above. For optional customization, copy it to `.env`, edit the copy, and use `--env-file .env`; `.env` is ignored by Git.

| Variable | Purpose | Example default |
| --- | --- | --- |
| `POSTGRES_DB` | Local database name | `media_streaming` |
| `POSTGRES_USER` | Local database user | `media_streaming` |
| `POSTGRES_PASSWORD` | Local database password | `local-only-change-me` |
| `MEDIA_BASE_URL` | Optional browser-facing media prefix; empty keeps stored `/media/...` references same-origin | empty |
| `SECURE_COOKIE` | Require HTTPS for refresh cookies | `false` for loopback HTTP |
| `REFRESH_COOKIE_NAME` | Refresh cookie name | `refresh_token` |

`PUBLIC_BASE_URL` is not an application setting: the browser uses the origin from which it loaded the frontend. Set `MEDIA_BASE_URL` only when media is published separately, for example `https://media.example.test/library`; catalog artwork and playback manifests stored below `/media/` are then returned below that prefix.

## Development and testing

Run the offline repository/tooling tests:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
```

Preview local acceptance without creating Docker resources:

```sh
python scripts/run_acceptance.py
```

Use `python scripts/run_acceptance.py --apply --quick` for a short local run or `python scripts/run_acceptance.py --apply` for the complete profile. See the [local acceptance runbook](docs/runbooks/local-acceptance.md) for backend, frontend, Compose, Nginx, and workflow checks. For frontend-only work, see the [frontend guide](frontend/README.md).

## Repository scope

This repository contains the LVFAST application, API contract, local media fixtures, tests, container images, CI, and local-development configuration. Deployment and hosting configuration intentionally live outside this repository.

The demo is synthetic and resettable. It does not provide DRM, transcoding, billing, administration, or durable customer-data guarantees.

## License

Licensed under the [MIT License](LICENSE).
