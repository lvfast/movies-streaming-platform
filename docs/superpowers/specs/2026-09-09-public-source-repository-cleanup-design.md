# Public Source Repository Cleanup Design

## Goal

Turn this repository into a self-contained public application repository that a new contributor can clone, configure, test, and run locally without access to the original infrastructure repository.

The application keeps the existing LVFAST product name and the current Java and npm namespaces. It must not assume a specific GitHub owner, production domain, cloud provider, CDN, private server, or deployment workflow.

## Repository boundary

This repository owns:

- Spring Boot backend source, database migrations, and backend tests;
- React frontend source, generated API client, and frontend tests;
- the OpenAPI contract;
- small, legally distributable local media fixtures and placeholder artwork;
- local Docker images and Docker Compose configuration;
- local acceptance tests;
- CI checks for the files and capabilities present in this repository;
- contributor-facing documentation and an MIT license.

This repository does not own:

- production deployment or rollback automation;
- home-server configuration;
- Cloudflare, R2, DNS, CDN, Terraform, Tailscale, or SSH configuration;
- production credentials, domains, image digests, monitoring, or alert delivery;
- references to files that exist only in the original infrastructure repository.

Those concerns belong in a separate private infrastructure repository. That repository may configure this application exclusively through documented environment variables and published container images.

## Cleanup strategy

### Preserve

- `backend/`, `frontend/`, `docs/api/`, local media fixtures, and their meaningful tests;
- the LVFAST display name;
- the `com.lvfast` Java package and `@lvfast` npm scope;
- security defaults that are useful in local or portable container execution;
- generic OCI metadata that can be resolved from the current build context.

### Remove or rewrite

- CI jobs and Dependabot entries for absent `infra/` and production Compose files;
- documentation about private-host operations, Cloudflare state, completed infrastructure tasks, and pending production acceptance;
- production-only variables from the public local environment example;
- hardcoded URLs for the original GitHub repository and `lvfast.site`;
- stale task-number terminology where it leaks historical implementation context;
- ignore rules that exist only for removed infrastructure directories;
- duplicated documentation whose content is already covered by the root README or local runbook.

A file will be deleted only when it has no role in the public application's local build, tests, runtime, or contributor documentation. Generated API client files and small media fixtures are intentional repository assets, not cleanup candidates.

## Configuration contract

Local execution uses safe defaults and `.env.example`. Host-specific settings remain configurable without editing source:

- database name, username, and password;
- Redis host, port, and optional password;
- public application base URL;
- media base URL;
- refresh-cookie security and name;
- backend and frontend image names when Compose consumes prebuilt images.

Application defaults must use localhost or neutral identifiers. Problem Details URLs should use a stable relative or neutral URI rather than a personal production domain. Browser security policy must allow local media by default and support a configurable external media origin only if the existing runtime can express that safely; otherwise the public repo documents the single production-side template substitution required in the separate infrastructure repo.

No real secret, production digest, absolute host path, repository owner, or deployment endpoint appears in tracked defaults.

## Local developer experience

The primary path is:

1. copy `.env.example` to `.env` if customization is needed;
2. run the local Compose stack;
3. open the loopback frontend URL;
4. run backend, frontend, and acceptance checks with documented commands.

The root README will explain prerequisites, repository layout, configuration, local startup, verification, and the separation between application and deployment concerns. Architecture documentation will describe only the application modules, local data flow, security behavior, and supported configuration boundary.

## CI and dependency automation

CI will validate only repository-owned assets:

- Maven tests;
- frontend tests, build, dependency audit, and generated-client drift;
- local Compose rendering;
- Nginx configuration;
- workflow linting;
- disposable local acceptance tests;
- backend and frontend image builds and vulnerability scans.

References to Terraform, Cloudflare verification scripts, production Compose overlays, and host-operations validation will be removed. Dependabot will retain Maven, npm, Docker, and GitHub Actions updates, and remove entries for absent infrastructure directories.

The release workflow may publish images to the current GitHub repository through `${{ github.repository }}`. Labels must not point to the old repository URL; source and revision metadata should be derived from GitHub context or omitted when a portable local Docker build cannot supply them.

## Validation and failure handling

Cleanup is complete only when:

- a case-insensitive repository scan finds no old GitHub repository URL, `lvfast.site`, absent infrastructure paths, or obsolete production instructions outside an explicit historical note (none is planned);
- every Markdown link resolves to a tracked local file or an intentional public URL;
- Docker Compose renders successfully from the retained files;
- backend unit/integration tests that do not require unavailable external services pass;
- frontend tests and production build pass;
- generated API client drift check passes;
- Python acceptance-runner unit tests pass;
- CI configuration references only existing files and commands.

If an existing test encodes a removed historical project name solely as a safety guard, it will be rewritten around the new neutral/local behavior rather than deleted. If validation reveals that a supposedly redundant asset is a runtime dependency, the asset will be retained and documented.

## Expected file-level changes

- Rewrite `README.md`, `docs/architecture.md`, and `docs/runbooks/local-acceptance.md`.
- Add `LICENSE` with the MIT text.
- Simplify `.env.example`, `.gitignore`, `.dockerignore`, `.github/dependabot.yml`, and `.github/workflows/ci.yml`.
- Update release and Docker image metadata to avoid a hardcoded source repository.
- Neutralize domain defaults and source URLs in backend/frontend configuration and security policy.
- Update tests affected by renamed acceptance artifacts or removed historical assumptions.
- Delete only documentation or configuration that is proven unused after reference and runtime checks.

## Non-goals

- Renaming LVFAST or changing Java/npm namespaces;
- redesigning application features or UI;
- adding a generic production deployment framework;
- deploying the application or modifying the new GitHub repository settings;
- changing API behavior except where a hardcoded infrastructure identifier is part of a response or configuration default;
- removing local fixtures merely to minimize repository size.
