# P8 — Integration and Release Evidence

**Outcome:** One isolated end-to-end journey proves the complete MVP across API, storage, RabbitMQ, worker, gateway and Admin/player UI; final relevant suites run once only if release evidence requires them.

**Depends on:** P7 DONE and all P1-P7 handoffs reviewed.

## Files

Create:

- `scripts/run_media_acceptance.py`
- `scripts/tests/test_run_media_acceptance.py`
- `frontend/e2e/admin-media-journey.spec.ts`
- `frontend/playwright.config.ts`
- `docs/runbooks/admin-media-acceptance.md`
- `docs/runbooks/admin-media-rollout.md`
- `.github/workflows/media-ci.yml`

Modify only when required by demonstrated integration gaps:

- `frontend/package.json` and lockfile for pinned Playwright tooling
- Compose media overlay
- backend/transcoder/gateway/frontend implementation with a focused regression test for each defect
- root/local documentation and CI build paths

## Acceptance journey

The isolated journey uses synthetic users and legally generated small fixtures:

1. Register an account and grant ADMIN with the operator command.
2. Create a draft and verify USER denial plus stale ETag rejection.
3. Upload poster, backdrop and video; interrupt/resume at least one multipart upload.
4. Observe RabbitMQ/worker processing and READY output.
5. Preview without writing personal progress, attach artwork and publish.
6. Open viewer playback through the gateway; decode HLS, require 401 without token and 206 for an authorized range.
7. Upload a replacement, activate it and prove an old session remains pinned while a new session uses the replacement.
8. Unpublish, Archive and Restore; verify new playback denial and restored lifecycle UNPUBLISHED.
9. Verify the audit view contains the important actions and no Delete action exists.

## Acceptance criteria

- **P8-A:** `scripts/run_media_acceptance.py` defaults to dry-run and creates no resources without `--apply`.
- **P8-B:** Applied local runs use an explicit unique Compose project name, bounded deadlines and cleanup limited to that project.
- **P8-C:** The complete journey above passes through actual APIs/services; mocks may isolate browser-only presentation but cannot replace storage, worker or gateway success.
- **P8-D:** Reports contain versions, assertions, durations and skips but no credentials, JWTs, signed URLs or raw source data.
- **P8-E:** Provider-only R2/Cloudflare checks are listed as PASS, FAIL or NOT_RUN with reason. Missing cloud access does not become a false pass and does not block local MVP completion.
- **P8-F:** Each full component suite, when required for the release decision, is run at most once after all integration fixes are complete.
- **P8-G:** Final documentation states remaining post-MVP limitations and whether the evidence supports local completion, staging readiness or neither.

## Implementation sequence

- [ ] Write `test_run_media_acceptance.py` for dry-run, explicit ownership scope and report redaction. Run only this file and implement the harness.
- [ ] Write the Playwright journey in the same order as the acceptance journey; use API setup only for authentication/fixtures that are not the behavior under test.
- [ ] Run the journey once and record actual integration gaps. For every product defect, add a focused regression test in the owning component before changing production code.
- [ ] Apply the two-fix-attempt rule to each distinct defect. Do not repeatedly rerun the full journey while a focused owning test is still red.
- [ ] Once each focused defect test passes, rerun only the failed journey step when the harness supports it; rerun the complete journey after all known integration gaps are resolved.
- [ ] Add/update CI so normal PR jobs use component-focused checks and the expensive media journey is one explicit integration job.
- [ ] Record R2/Cloudflare staging commands and required external inputs in the runbook. Do not create/deploy resources unless separately authorized.

## Optional single final-suite gate

After the complete journey passes and no production code changes afterward, decide whether a release decision requires the full suites. If yes, run each command once:

```powershell
mvn -f backend/pom.xml --batch-mode --no-transfer-progress test
mvn -f transcoder/pom.xml --batch-mode --no-transfer-progress test
npm.cmd --prefix media-gateway test
npm.cmd --prefix media-gateway run check
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
python -m unittest discover -s scripts/tests -p 'test_*.py' -v
```

Do not rerun a successful suite unless production code in that component changes afterward. If a full-suite failure appears, switch to its focused test and diagnostic loop; after the focused fix passes, rerun only that component's suite once for final evidence.

- [ ] Write the final report with exact commands/exits, test counts/skips, browser/service versions, staging NOT_RUN reasons and post-MVP limitations.
- [ ] Update the ledger and write `handoffs/P8.md`. Stop; deployment and merge require a separate request.

## Packet completion evidence

Local MVP completion requires the complete isolated journey plus the directly relevant checks. A production-readiness claim additionally requires separately authorized provider evidence; this packet does not infer it. Full-suite execution is optional unless the requested release decision needs it.

Commit explicit P8 paths with message `test(admin-media): complete P8 integration evidence` when committing is authorized.
