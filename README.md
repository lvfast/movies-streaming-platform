# LVFAST Media Streaming Platform

Portfolio-oriented movie streaming demo with a React frontend, a Java 21/Spring Boot backend, PostgreSQL, Redis, 20 seeded movies, and three local HLS fixtures. Guests can browse and search; registered users can manage a watchlist, play a fixture, and resume viewing progress.

This is a resettable synthetic demo. Accounts and viewing data may be deleted at any time, and there is no backup, RPO, or RTO commitment.

## Documentation

- [Architecture, API, data, and security](docs/architecture.md)
- [Public OpenAPI 3.1 contract](docs/api/openapi.yaml)
- [Local acceptance and complete repository checks](docs/runbooks/local-acceptance.md)
- [Cloudflare R2 media operations and cost boundary](docs/runbooks/cloudflare-r2-media.md)
- [Portable private-host deployment, rollback, and observability bundle](infra/prod-host-ops/README.md)
- [Frontend-only development](frontend/README.md)

## Start the local demo

Docker Desktop with Compose v2 is the only required runtime:

```sh
docker compose -f compose.yml -f compose.local.yml up --build
```

Open <http://localhost:8080>. The local overlay creates reusable development-only RSA keys in a named Docker volume and publishes only the Nginx entrypoint on loopback. The backend, frontend, and Redis run as unprivileged users. The official PostgreSQL entrypoint starts as root to repair volume ownership, then drops to the `postgres` user with a small required capability set.

Stop the stack without deleting account data:

```sh
docker compose -f compose.yml -f compose.local.yml down
```

Add `--volumes` only when intentionally resetting PostgreSQL data and the local JWT keys. Rebuilding the stack reimports the versioned catalog manifest idempotently.

The API is under `/api/v1`; its request and response schemas are in the [OpenAPI contract](docs/api/openapi.yaml). API and load acceptance use a separate disposable stack and are documented in the [local acceptance runbook](docs/runbooks/local-acceptance.md). Running `python scripts/run_acceptance.py` is a safe dry-run; `--apply` is required to create local Docker resources.

## Delivery and operations boundary

Production configuration is an overlay and requires externally supplied image digests, database credentials, and JWT key files. It publishes no host ports:

```sh
docker compose -f compose.yml -f compose.prod.yml config
```

The public repository owns CI, release image publication, credential-free configuration checks, and disposable local acceptance. It does not deploy the host. Task 7 host deployment and observability assets are in the [portable prod-host-ops bundle](infra/prod-host-ops/README.md), including local-only validation, Tailscale workflow, image rollback, dashboards, and bounded metrics/log storage. The bundle is a template for the private operations repository; live Linux ownership/filesystem checks, Tailscale/SSH policy, deployment/rollback, public routing, and alert delivery remain separate operator acceptance.

Cloudflare R2 infrastructure and publication are also separate credentialed operations. The bucket, CORS, custom domain, and disabled `r2.dev` endpoint have been applied, while the cache rule, media upload, and live CDN HIT/range/CORS verification remain pending. The [media runbook](docs/runbooks/cloudflare-r2-media.md) records the current cost model and must be checked against Cloudflare's current pricing before any approved publication.
