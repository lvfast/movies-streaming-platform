# Public Source Repository Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the LVFAST application a self-contained public repository that clones, tests, and runs locally without links or dependencies tied to the original infrastructure repository.

**Architecture:** Preserve the backend, frontend, API contract, local media, and application namespaces while narrowing the repository boundary to application and local-development concerns. Replace production-specific identifiers with neutral configuration, make acceptance tooling use durable names, then align automation and contributor documentation with the files that remain.

**Tech Stack:** Java 21, Spring Boot, Maven, React 19, TypeScript 6, Vite 8, Vitest, Nginx, PostgreSQL 17, Redis 7.4, Docker Compose v2, Python 3.10+, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-09-public-source-repository-cleanup-design.md`

## Global Constraints

- Keep the LVFAST display name, `com.lvfast` Java package, and `@lvfast` npm scope.
- Do not add production deployment, home-server, Cloudflare, DNS, CDN, Terraform, Tailscale, SSH, monitoring, or alerting assets.
- Tracked defaults may not contain a real production domain, old repository URL, repository owner, credential, image digest, or absolute host path.
- Preserve generated API client files and the small local media fixtures.
- Keep local execution on loopback through `compose.yml` plus `compose.local.yml`.
- Delete a file only after confirming that local build, tests, runtime, and documentation do not reference it.

---

### Task 1: Neutralize application and image metadata

**Files:**
- Modify: `backend/src/test/java/com/lvfast/streaming/common/ApiExceptionHandlerTest.java`
- Modify: `backend/src/test/java/com/lvfast/streaming/identity/JwtAccessTokenIssuerTest.java`
- Modify: `backend/src/main/java/com/lvfast/streaming/common/ApiExceptionHandler.java`
- Modify: `backend/src/main/java/com/lvfast/streaming/common/ProblemResponseWriter.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/Dockerfile`
- Modify: `frontend/Dockerfile`
- Modify: `frontend/nginx.conf`

**Interfaces:**
- Consumes: existing RFC 9457 Problem Details responses and the `JWT_ISSUER` environment variable.
- Produces: problem types shaped as `urn:lvfast:problem:<lowercase-kebab-code>`; default JWT issuer `http://localhost:8080`; same-origin-only local CSP; OCI source label supplied by optional `VCS_URL` build argument.

- [ ] **Step 1: Add failing assertions for neutral Problem Details identifiers**

In `ApiExceptionHandlerTest`, update the existing Problem Details assertion to require this exact type:

```java
assertThat(problem.getType()).isEqualTo(URI.create("urn:lvfast:problem:validation-error"));
```

Add a focused `MockHttpServletResponse` assertion for the security writer if the class does not already exercise `ProblemResponseWriter.write`:

```java
assertThat(response.getContentAsString())
        .contains("\"type\":\"urn:lvfast:problem:invalid-token\"");
```

- [ ] **Step 2: Replace production-domain values in issuer tests and verify failure**

Use `https://issuer.example` only as an explicit constructor input in `JwtAccessTokenIssuerTest`, then expect that value in the JWT. Run:

```sh
cd backend
mvn --batch-mode --no-transfer-progress -Dtest=ApiExceptionHandlerTest,JwtAccessTokenIssuerTest test
```

Expected: the Problem Details assertion fails because production-domain URLs are still emitted; JWT tests pass with the neutral example issuer.

- [ ] **Step 3: Implement neutral runtime defaults**

Change both Problem Details writers to construct:

```java
URI.create("urn:lvfast:problem:" + code.toLowerCase().replace('_', '-'))
```

For the manual JSON writer, emit the same string value directly. Change `application.yml` to:

```yaml
issuer: ${JWT_ISSUER:http://localhost:8080}
```

Remove the fixed external media origin from `frontend/nginx.conf`; retain `'self'`, `data:` for images, and `blob:` for media. Add this build argument before the OCI labels in each Dockerfile:

```dockerfile
ARG VCS_URL=""
```

and set:

```dockerfile
org.opencontainers.image.source="${VCS_URL}"
```

- [ ] **Step 4: Run focused tests and configuration checks**

Run:

```sh
cd backend
mvn --batch-mode --no-transfer-progress -Dtest=ApiExceptionHandlerTest,JwtAccessTokenIssuerTest test
cd ..
docker compose -f compose.yml -f compose.local.yml config --quiet
docker run --rm --add-host backend:127.0.0.1 -v "$PWD/frontend/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:1.28-alpine nginx -t
```

Expected: all commands exit 0 and no output contains `lvfast.site` or the old GitHub repository URL.

- [ ] **Step 5: Commit**

```sh
git add backend/src frontend/Dockerfile frontend/nginx.conf backend/Dockerfile
git commit -m "refactor: remove deployment-specific runtime identifiers"
```

### Task 2: Give local acceptance tooling durable public names

**Files:**
- Modify: `scripts/tests/test_run_acceptance.py`
- Modify: `scripts/run_acceptance.py`
- Modify: `tests/acceptance/api.js`

**Interfaces:**
- Consumes: `execute(apply: bool, quick: bool, output: Path)` and Docker Compose JSON.
- Produces: disposable projects named `local-acceptance-<10 hex>`; test containers suffixed with `-api` or `-load`; reports under `artifacts/acceptance/`.

- [ ] **Step 1: Update tests to express the public naming contract**

Replace every valid `task8-acceptance-1234567890` fixture with `local-acceptance-1234567890`. Assert unsafe values are rejected:

```python
for name in ("lvfast", "media-streaming-platform", "local-acceptance-../", "local-acceptance-"):
    with self.subTest(name=name), self.assertRaises(ValueError):
        runner.check_project(name)
```

Add an assertion that `runner.execute()` defaults beneath `artifacts/acceptance`, using a patched `ROOT` or the existing dry-run mechanism without creating Docker resources.

- [ ] **Step 2: Run the unit tests to verify they fail**

Run:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
```

Expected: naming-contract assertions fail because the implementation still accepts only `task8-acceptance-*`.

- [ ] **Step 3: Implement the naming change**

In `scripts/run_acceptance.py`, use these exact constants:

```python
PROJECT_PREFIX = "local-acceptance-"
IMAGE_TAG = "acceptance"
DEFAULT_OUTPUT = ROOT / "artifacts/acceptance"
```

Use `rf"{re.escape(PROJECT_PREFIX)}[0-9a-f]{{10}}"` in `check_project`, validate container names as the project pattern followed by `-(api|load)`, construct the project from `PROJECT_PREFIX`, tag application images with `IMAGE_TAG`, and make `execute(..., output=DEFAULT_OUTPUT)` the default. Change the acceptance request ID in `tests/acceptance/api.js` from `task8-invalid-page` to `acceptance-invalid-page`.

- [ ] **Step 4: Run tests and inspect the dry-run**

Run:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
python scripts/run_acceptance.py
```

Expected: tests pass; dry-run performs no Docker calls and describes only disposable local resources.

- [ ] **Step 5: Commit**

```sh
git add scripts/run_acceptance.py scripts/tests/test_run_acceptance.py tests/acceptance/api.js
git commit -m "refactor: make acceptance tooling repository-neutral"
```

### Task 3: Align CI and dependency automation with repository contents

**Files:**
- Create: `scripts/tests/test_repository_policy.py`
- Modify: `.github/dependabot.yml`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `.env.example`
- Modify: `.gitignore`
- Modify: `.dockerignore`

**Interfaces:**
- Consumes: files tracked by the application repository and the optional `VCS_URL` Docker build argument from Task 1.
- Produces: an offline repository-policy unit test; CI jobs that reference only present application assets; releases whose source metadata derives from `${{ github.server_url }}/${{ github.repository }}`.

- [ ] **Step 1: Add a failing offline repository policy test**

Create `scripts/tests/test_repository_policy.py` with a `unittest.TestCase` that scans tracked application/config/documentation files while excluding `.git/` and `docs/superpowers/`. It must fail when text contains any of:

```python
BANNED = (
    "github.com/lvfast/media-streaming-platform",
    "lvfast.site",
    "compose.prod.yml",
    "infra/cloudflare",
    "infra/prod-host-ops",
    "task8-acceptance",
)
```

Add a Markdown-link check for relative links in `README.md`, `docs/architecture.md`, and `docs/runbooks/local-acceptance.md`: strip any `#fragment`, resolve relative to the containing file, and assert the target exists. Ignore `http:`, `https:`, `mailto:`, and pure `#fragment` links.

- [ ] **Step 2: Run the policy test to verify the inherited configuration fails**

Run:

```sh
python -m unittest scripts.tests.test_repository_policy -v
```

Expected: FAIL with old repository/domain, missing infrastructure path, historical acceptance name, and broken local-link findings.

- [ ] **Step 3: Simplify automation and public defaults**

Remove the npm and Terraform Dependabot entries for `/infra/cloudflare`. In CI:

- remove Terraform setup, Task 6 verification, Cloudflare validation, production Compose rendering, and production-only environment variables;
- retain Python unit tests, local Compose rendering, Nginx validation, actionlint, backend/frontend tests, local acceptance, image builds, and scans;
- change the acceptance artifact path to `artifacts/acceptance/**/*.json`;
- run `python -m unittest discover -s scripts/tests -p "test_*.py" -v` as the repository policy/tooling check.

In the release workflow, supply this build argument:

```yaml
build-args: |
  APP_VERSION=${{ github.ref_name }}
  VCS_REF=${{ github.sha }}
  VCS_URL=${{ github.server_url }}/${{ github.repository }}
```

Remove `GHCR_REPOSITORY`, image digests, and absolute JWT key paths from `.env.example`. Retain only variables consumed by local Compose. Remove ignore patterns dedicated to absent infra and toolchain directories, but retain secrets, build output, coverage, acceptance artifacts, IDE metadata, and `.env` exclusions. Keep `.dockerignore` limited to VCS/IDE metadata, secrets, dependencies, build outputs, coverage, and internal planning/handoff documents.

- [ ] **Step 4: Run policy, Compose, and workflow checks**

Run:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
docker compose --env-file .env.example -f compose.yml -f compose.local.yml config --quiet
docker run --rm -v "$PWD:/repo" --workdir /repo rhysd/actionlint:1.7.12 -color
```

Expected: policy may still fail only on documentation scheduled for Task 4; Compose and actionlint exit 0. Inspect the remaining policy failures and confirm every one belongs to those three documentation files.

- [ ] **Step 5: Commit**

```sh
git add .github .env.example .gitignore .dockerignore scripts/tests/test_repository_policy.py
git commit -m "ci: scope automation to the public application repository"
```

### Task 4: Rewrite public documentation and add the license

**Files:**
- Create: `LICENSE`
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/runbooks/local-acceptance.md`
- Modify: `frontend/README.md`

**Interfaces:**
- Consumes: the local commands, configuration variables, acceptance paths, and repository boundary established in Tasks 1–3.
- Produces: a clone-to-run onboarding path, application-only architecture reference, local verification runbook, frontend development guide, and MIT licensing terms.

- [ ] **Step 1: Rewrite the root onboarding document**

Keep the title and concise product description. Include these sections with executable commands:

```markdown
## Features
## Architecture
## Prerequisites
## Quick start
## Configuration
## Development and testing
## Repository scope
## License
```

Quick start must use `docker compose --env-file .env.example -f compose.yml -f compose.local.yml up --build`, link only to existing files, explain that `.env` is optional customization, and state that deployment/hosting configuration intentionally lives outside this repository without naming a private host or provider.

- [ ] **Step 2: Rewrite architecture and local verification docs**

In `docs/architecture.md`, retain the application module table, API behavior, persistence/cache responsibilities, local Nginx data flow, security behavior, and limitations. Remove the production CDN node, infrastructure state, task history, private-host observability, and production acceptance claims.

In `docs/runbooks/local-acceptance.md`, document only Python, Docker, Java/Maven, and Node/npm prerequisites; the dry-run/quick/full commands; `local-acceptance-<id>` isolation; `artifacts/acceptance/` reports; Maven/frontend checks; local Compose rendering; Nginx validation; actionlint; and the limits of local acceptance.

Update `frontend/README.md` only where it points to stale setup or external deployment assumptions. Do not duplicate the root README.

- [ ] **Step 3: Add the MIT license**

Create `LICENSE` using the standard MIT License text. Use:

```text
Copyright (c) 2026 LVFAST
```

Do not add a personal name or link. Link `LICENSE` from the root README.

- [ ] **Step 4: Run the repository policy test**

Run:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
```

Expected: PASS with no banned source URL, production domain, absent infrastructure path, stale task prefix, or broken local Markdown link.

- [ ] **Step 5: Commit**

```sh
git add LICENSE README.md docs/architecture.md docs/runbooks/local-acceptance.md frontend/README.md
git commit -m "docs: publish standalone local development guide"
```

### Task 5: Full repository verification and cleanup audit

**Files:**
- Modify only if a verification failure identifies a defect in files changed by Tasks 1–4.

**Interfaces:**
- Consumes: the complete cleaned repository.
- Produces: recorded evidence that source, tests, automation, documentation, and local configuration agree.

- [ ] **Step 1: Scan for stale coupling and inspect all remaining matches**

Run:

```sh
rg -n -i "github\.com/lvfast/media-streaming-platform|lvfast\.site|compose\.prod\.yml|infra/(cloudflare|prod-host-ops)|task[0-9]+|production HLS|Cloudflare|Tailscale|Terraform" -g "!docs/superpowers/**" -g "!.git/**" .
```

Expected: no matches. If a match is required application terminology rather than inherited coupling, document why before retaining it.

- [ ] **Step 2: Run backend verification**

Run:

```sh
cd backend
mvn --batch-mode --no-transfer-progress test
cd ..
```

Expected: BUILD SUCCESS. Record any Testcontainers skips separately; do not describe skipped integration tests as passing evidence.

- [ ] **Step 3: Run frontend verification**

Run:

```sh
cd frontend
npm ci
npm test
npm run build
npm run check:api
npm audit --audit-level=high
cd ..
```

Expected: all commands exit 0 and `check:api` reports no generated-client diff.

- [ ] **Step 4: Run repository and container configuration verification**

Run:

```sh
python -m unittest discover -s scripts/tests -p "test_*.py" -v
docker compose --env-file .env.example -f compose.yml -f compose.local.yml config --quiet
docker run --rm --add-host backend:127.0.0.1 -v "$PWD/frontend/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:1.28-alpine nginx -t
docker run --rm -v "$PWD:/repo" --workdir /repo rhysd/actionlint:1.7.12 -color
```

Expected: every command exits 0.

- [ ] **Step 5: Inspect the final diff and tracked-file boundary**

Run:

```sh
git status --short
git diff --check
git diff --stat HEAD~4..HEAD
rg --files
```

Expected: no whitespace errors, no build outputs/secrets, and no unexpected deletion of application fixtures. Because this repository began with an empty history, verify that every remaining untracked file belongs to the approved public boundary: `compose*.yml`, `trivy.yaml`, `backend/`, `frontend/`, `media/`, `tests/`, and any source files not already committed by earlier tasks.

- [ ] **Step 6: Commit the remaining initial application import**

Stage the approved remaining source paths explicitly, review the index, then commit:

```sh
git add compose.yml compose.local.yml trivy.yaml backend frontend media tests
git status --short
git diff --cached --check
git commit -m "feat: import standalone media streaming application"
```

Do not use `git add .`. Before committing, confirm the staged set contains no `.env`, key, dependency directory, build output, acceptance artifact, or infrastructure file. If verification fixes touched an already tracked file, include that exact file in this final commit and describe the correction in the handoff.

- [ ] **Step 7: Confirm a clean, complete repository**

Run:

```sh
git status --short
git ls-files
```

Expected: `git status --short` is empty and `git ls-files` includes the application, local runtime, documentation, automation, tests, fixtures, and license defined by the spec.
