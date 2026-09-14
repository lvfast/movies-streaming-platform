# Admin Media Packet Session Guide

## Scope

An implementation session owns exactly one selected packet. Internal acceptance IDs do not create additional sessions. Read the current source and dependency handoff before editing because repository paths and interfaces may have changed since this plan was written.

Do not silently implement a later packet, deploy external infrastructure, merge branches or delete retained media. Preserve unrelated user changes.

## Required workflow

1. Invoke `superpowers:using-superpowers` and `superpowers:executing-plans`.
2. Use an existing isolated feature branch/worktree or create one only when the execution environment requires it.
3. Mark the selected packet `IN_PROGRESS` and record the starting commit in `handoffs/Px.md`.
4. Use `superpowers:test-driven-development` for each new behavior or defect.
5. Use `superpowers:systematic-debugging` for an unexpected failure before proposing a fix.
6. Use `superpowers:verification-before-completion` for the packet's focused completion gate.
7. Review the packet diff locally. Request a separate review only when the user explicitly requests delegation/review or when the environment provides an already-authorized reviewer.
8. Write one handoff, update the ledger and stop. Do not begin the next packet.

## Selecting tests

A test is directly affected when the changed production code is on its execution path, a public contract it compiles against changed, or it is the regression that specifies the behavior being implemented.

| Changed area | Focused tests | Direct neighbors allowed at packet gate |
| --- | --- | --- |
| Identity/security/admin API | Named admin HTTP test | Existing auth HTTP test and OpenAPI contract test |
| Movie schema/catalog cache/import | Named migration/cache test | Existing catalog Testcontainers test and ArchitectureTest |
| Storage/upload | Named storage or upload test | Media schema/upload test only |
| Job state/Rabbit consumer | Named job/result test | Processing happy-path or recovery test only |
| FFmpeg/artwork | Named transcoder test | Worker happy-path integration test only |
| Playback/token/gateway | Named playback/token/gateway test | Existing player or playback test only |
| Admin React UI | Named component/page test | Admin API wrapper test and frontend build when types/routes changed |
| Integration harness | Named scenario | Full suites only when P8 explicitly calls for them |

Do not treat “might regress” as evidence that a test is affected. Record why each neighboring test was selected.

## Red/green loop

For one coherent behavior:

- [ ] Name the production behavior that would make the test pass.
- [ ] Add/select the smallest behavioral test.
- [ ] Run only that test and confirm it fails for the missing behavior, not fixture/setup errors.
- [ ] Implement the minimum production change.
- [ ] Run only that test until it passes or the failure policy stops the loop.
- [ ] Refactor only while the same focused test remains green.

Do not manufacture a failing test for generated files, documentation, static configuration or visual design. Validate those artifacts with their owning check.

## Two-fix-attempt failure policy

An “attempt” is a code/configuration change made to fix the same observed failure. Rerunning an unchanged test to gather diagnostics does not consume an attempt.

When a test fails unexpectedly:

1. Capture the failing assertion/error and identify a concrete hypothesis.
2. Apply the first focused fix and rerun only the failed test.
3. If it still fails, update the hypothesis from new evidence, apply one second focused fix and rerun only the failed test.
4. If it still fails, stop the packet. Do not try a third fix or broaden the suite.

The blocker report must contain:

```text
Command:
Failure:
Suspected cause:
Attempt 1:
Attempt 2:
Exact next diagnostic action:
```

Mark the packet `BLOCKED` unless the remaining failure is explicitly proven unrelated to its changed code; in that case record the evidence and leave the packet `REVIEW` for user judgment.

## Verification cadence

- During implementation: one focused test.
- After a changed shared interface: focused test plus named direct consumer test.
- End of packet: the packet's completion commands, once after the last relevant change.
- P4: only the three required recovery scenarios.
- P8: final integration journey, then each full repository suite at most once if release evidence requires it.

Do not rerun already-passing commands when no relevant file changed afterward. A documentation-only handoff edit does not invalidate code test evidence.

## Environment conventions

Run commands from the repository root unless the packet says otherwise.

```powershell
mvn -f backend/pom.xml --batch-mode --no-transfer-progress -Dtest=SelectedTest test
mvn -f transcoder/pom.xml --batch-mode --no-transfer-progress -Dtest=SelectedTest test
npm.cmd --prefix media-gateway test -- selected.test.ts
npm.cmd --prefix frontend test -- selected.test.tsx
```

Docker/Testcontainers absence is missing integration evidence, not a passing result. Do not replace a required real boundary test with a mock merely to obtain green output.

## Handoff

Create `docs/superpowers/plans/2026-09-10-admin-media/handoffs/Px.md` with concise actual values:

```text
# Px — packet name
Status: IN_PROGRESS | BLOCKED | REVIEW | DONE
Start commit:
End commit or uncommitted files:
Implemented acceptance IDs:
Files changed:
Focused red evidence:
Focused green evidence:
Direct neighboring tests and why they were affected:
Skipped/unavailable evidence:
Diff/contract review findings:
Deferred items added to post-MVP:
Outstanding blocker or exact next packet:
```

One handoff per packet is sufficient. Do not create a handoff per internal acceptance ID.
