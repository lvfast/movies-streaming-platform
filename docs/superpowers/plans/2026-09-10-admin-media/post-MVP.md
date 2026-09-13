# Admin Media Post-MVP Backlog

This backlog holds hardening and expansion deliberately excluded from the eight MVP packets. Items move into implementation only after a separate design/priority decision.

## Distributed recovery hardening

- Exhaustive worker/broker/API crash-interleaving matrix beyond the three P4 scenarios.
- Concurrent movie creation with the same Idempotency-Key: the second request can surface a 409
  slug conflict instead of replaying the first response; the unique constraint still prevents
  duplicate durable resources and sequential replay returns the identical movie.
- Concurrent upload create with the same Idempotency-Key can surface a 409 "replacement in
  progress" instead of replaying the first session; sequential replay returns the identical
  session and the unique nonterminal constraint prevents duplicate durable resources (P2).
- Concurrent abort and complete on the same OPEN session: exactly one guarded transition wins,
  but the loser can surface a 409/503 and the storage multipart upload can be aborted mid-flight
  (P2). Exactly-once job creation is preserved by the guarded COMPLETING-to-COMPLETED transition.
- Upload completion validates the Idempotency-Key header per the shared convention but deduplicates
  via the session state machine plus reserved-object HeadObject reconciliation, not an
  operation_request replay row (P2).
- Multiple simultaneous watchdog/publisher reconciliation stress tests.
- Poison-message replay tooling and operator-controlled dead-letter replay.
- Broker cluster failure and quorum-queue capacity evidence.
- Long-duration soak tests with many workers and API instances.

## Storage and lifecycle operations

- Apply-mode orphan/failed-attempt object deletion with reference rechecks.
- Automated incomplete multipart reconciliation beyond storage lifecycle and the bounded MVP check.
- A failed upload-create database transaction can orphan a storage multipart upload that was
  already initiated; the MVP aborts it best-effort and relies on storage lifecycle for the rest (P2).
- Retention policies for old successful versions and archived content.
- Automated backup/restore orchestration and point-in-time recovery rehearsal.
- R2 inventory reconciliation and provider-specific retention controls.

## Security and compliance hardening

- Automated media signing-key rotation and overlap rehearsal.
- Separate migration/runtime database identities with verified audit UPDATE/DELETE denial.
- Compliance-grade immutable audit export/storage.
- More granular administrator roles and Dashboard role management.
- DRM, token exchange or stronger controls against bearer-token sharing.

## Delivery and protocol expansion

- Adaptive bitrate HLS ladder.
- Subtitle ingestion and playback.
- HDR tone mapping rather than MVP rejection.
- Native HLS, mobile and TV clients.
- Multiple audio tracks and language selection.
- Conditional/range/cache variations not required by the MVP player.
- Media-token key rotation overlap: the gateway selects keys by `kid` from a configured JWK or JWK
  Set, so an overlap window is possible, but no automated publishing of both keys is implemented
  (P5). The MVP loads one media key pair per application start.
- Media playback sessions are never garbage-collected. Expired or revoked rows stay until a later
  cleanup decides their retention, and the MVP keeps progress rows pointing at them (P5).

## Operations and product expansion

- Automated production cutover/rollback orchestration.
- Calibrated capacity, cost and SLO models based on production traffic.
- Full alert rules and external alert delivery.
- Analytics dashboards and richer operational summaries.
- Bulk editing, permanent deletion workflows and export/report features.
- Server-side search, lifecycle/active-media filtering and sort for the Admin library. The P5
  `adminListMovies` contract only accepts `page`/`size`, so the P6 library fetches every page and
  applies search, filters and sort client-side. Moving these into the query is a UX/scale
  improvement, not an MVP requirement (P6).
- Per-version media state (UPLOADING/QUEUED/PROCESSING/READY/FAILED) in the Admin library. The P6
  list contract only exposes `activeMediaVersionId`, so the library's "active media" column/filter
  is a coarse active-version indicator, not a processing state. Surfacing real media state in the
  library needs a contract extension and the P7 media-version/asset data (P6).
- Richer audit filtering. The P7 `adminListAudit` contract supports exact-match `action`,
  `entityType` and `entityId` plus zero-based paging; free-text search, actor filtering and
  date-range windows are deferred (P7).
- Automated Admin browser journey. P7 ships the admin media UI and its focused component tests, but
  the consolidated upload/READY/preview/publish/audit browser journey is owned by the P8 Playwright
  integration packet; there is no reusable admin browser harness before P8 (P7).

## Test infrastructure

- Backend full-suite context/container defect: `MediaTokenTest` (last of the `ApiTestSupport`
  subclasses in a full run) reuses a Spring-cached context whose datasource still points at an
  earlier class's dead Testcontainers Postgres port, so all 7 of its tests error with
  `CannotGetJdbcConnection` while every other test passes (0 failures). The class passes alone and
  in pairs; the defect only appears after ~17 container cycles in one fork. It is unrelated to P8
  production code (reproduces with all P8 test classes excluded) and blocks the optional full-suite
  release gate, not the local MVP completion evidence (P8).

## Recording new deferred work

Append a short item under the matching section with:

- the behavior or risk;
- why it is not necessary for the current MVP packet;
- which packet or production evidence revealed it.

Do not use this file as permission to implement an item opportunistically.
