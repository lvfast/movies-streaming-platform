# Admin and Private Media MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement one selected packet. Use subagents only when the user explicitly requests delegation. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a secure, usable admin workflow for creating, uploading, processing, publishing and replacing films without spending the MVP on unlikely edge cases.

**Architecture:** Extend the Spring Boot application with admin/media modules, use private S3-compatible storage for uploads, RabbitMQ plus an independent Java FFmpeg worker for processing, and a Cloudflare Worker for protected HLS delivery. Build and verify vertical slices so each packet ends in user-visible or operationally testable behavior.

**Tech Stack:** Java 21, Spring Boot 4.0.8, PostgreSQL 17, Redis, RabbitMQ, Cloudflare R2, FFmpeg, React 19.2.8, TypeScript 6.0.3, HLS.js 1.7.2 and Vitest 5.0.0.

**Spec:** [Admin and Private Media Design](../../specs/2026-09-10-admin-media-design.md)

## Global constraints

- New product copy, validation/error text, code comments and project documents use English.
- Add Flyway migrations; never edit `V1__initial_schema.sql`.
- R2 source and delivery buckets stay private. Browsers receive presigned operations, never storage credentials.
- RabbitMQ processing runs in an independent Java worker with no application database access.
- Produce one H.264/AAC rendition within 1920x1080, preserving aspect ratio and without upscaling.
- Publishing and replacement activation are explicit. A failed replacement cannot disturb the active version.
- The Dashboard has no permanent movie/media deletion and no role-management screen.
- Preserve the installed framework versions unless the introducing packet records a required compatibility change.
- Do not provision cloud resources, deploy, merge or delete retained media without a separate user request.

---

## How to use this package

Read the design spec, [contracts](contracts.md), [session guide](00-session-guide.md), the selected packet and its dependency handoff. Implement exactly one packet per working session and stop after its completion gate.

Acceptance IDs inside a packet are tracking points, not separate sessions, reviews or commits. Keep one concise handoff and normally one commit per packet. Do not restart completed work when a handoff contains current evidence.

## Packet map

| Packet | Plan | Depends on | Completion outcome | Status |
| --- | --- | --- | --- | --- |
| P1 | [Admin foundation](01-admin-foundation.md) | none | Operator grants ADMIN; admin safely creates/edits managed drafts | NOT_STARTED |
| P2 | [Direct uploads](02-direct-uploads.md) | P1 | Private multipart uploads resume/complete/abort and create one queued job | NOT_STARTED |
| P3 | [Processing happy path](03-processing-happy-path.md) | P2 | RabbitMQ worker produces verified READY HLS and artwork | NOT_STARTED |
| P4 | [Processing reliability](04-processing-reliability.md) | P3 | Lease recovery, bounded retry and three high-value failure cases pass | NOT_STARTED |
| P5 | [Publish and playback](05-publish-playback.md) | P4 | Published managed media plays through token-protected HLS | NOT_STARTED |
| P6 | [Admin core UI](06-admin-core-ui.md) | P5 | Protected Admin library and draft editor work with generated contracts | NOT_STARTED |
| P7 | [Admin media UI](07-admin-media-ui.md) | P6 | Upload, jobs, preview, lifecycle and audit journeys work | NOT_STARTED |
| P8 | [Integration and release](08-integration-release.md) | P7 | One complete journey and final relevant suites provide release evidence | NOT_STARTED |

All statuses start at `NOT_STARTED`. The deleted plan marked earlier tasks `REVIEW`, but the current worktree had no corresponding implementation or handoff evidence; the new ledger does not carry those unsupported statuses forward.

## Delivery order and stop conditions

Each packet produces a coherent vertical result:

```text
P1 admin data/control
  -> P2 source ingestion
  -> P3 usable processed output
  -> P4 minimum recovery guarantees
  -> P5 publish and viewer playback
  -> P6 core Admin UI
  -> P7 media operations UI
  -> P8 integrated evidence
```

Do not start the next packet automatically. If a cloud-only check is unavailable, record it honestly; local work may complete when the packet explicitly treats the cloud check as external evidence rather than a local acceptance requirement.

## Test policy

For each coherent behavior:

1. Write or select one focused behavioral test and observe the expected failure before production implementation.
2. Implement the smallest change that satisfies the behavior.
3. Run that focused test again.
4. Run directly affected neighboring tests only when the changed interface or shared behavior reaches them.
5. Do not run an unrelated module or the full repository suite.

When an unexpected failure occurs, diagnose it before editing. After two unsuccessful fix attempts for the same failure, stop and report:

- exact test command;
- short error summary;
- suspected cause;
- first attempted fix;
- second attempted fix.

When the packet's relevant tests pass, perform its diff/contract check, write the handoff and end the session. P8 owns the only optional full-suite run.

## Definition of done for every packet

- Every acceptance criterion in the selected packet is satisfied or explicitly reported as blocked.
- Tests named by the packet and directly affected tests pass with fresh output.
- No unrelated file, secret, signed URL, JWT or storage credential appears in the diff/logs.
- Changed public endpoints/types are reflected in `docs/api/openapi.yaml` and generated consumers when the packet owns generation.
- Deferred complexity is added to [post-MVP.md](post-MVP.md), not implemented opportunistically.
- `handoffs/Px.md` records changes, evidence, limitations and the exact next packet.
- The ledger status is updated to `DONE`, `BLOCKED` or `REVIEW`; never infer completion from code presence alone.

## First packet prompt

```text
Use @Superpowers and implement only P1 from
docs/superpowers/plans/2026-09-10-admin-media/01-admin-foundation.md.
Read the design spec, README.md, 00-session-guide.md and contracts.md first.
Use the focused-test and two-fix-attempt policies exactly. Do not run the full
suite and do not start P2. Keep new project content in English. Update the P1
handoff and ledger before stopping.
```

## Resume prompt

```text
Use @Superpowers and implement only <PACKET> from <PACKET_FILE>.
Read the design spec, README.md, 00-session-guide.md, contracts.md and the
dependency handoff. Continue from recorded evidence instead of repeating passed
checks. Diagnose unexpected test failures before editing, rerun only affected
tests, and stop after two unsuccessful fixes to the same failure. Do not run the
full suite unless this is P8 and the integration gate requires it. Update the
packet handoff and ledger, then stop.
```
