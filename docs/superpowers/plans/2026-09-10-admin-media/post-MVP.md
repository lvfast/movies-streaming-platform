# Admin Media Post-MVP Backlog

This backlog holds hardening and expansion deliberately excluded from the eight MVP packets. Items move into implementation only after a separate design/priority decision.

## Distributed recovery hardening

- Exhaustive worker/broker/API crash-interleaving matrix beyond the three P4 scenarios.
- Multiple simultaneous watchdog/publisher reconciliation stress tests.
- Poison-message replay tooling and operator-controlled dead-letter replay.
- Broker cluster failure and quorum-queue capacity evidence.
- Long-duration soak tests with many workers and API instances.

## Storage and lifecycle operations

- Apply-mode orphan/failed-attempt object deletion with reference rechecks.
- Automated incomplete multipart reconciliation beyond storage lifecycle and the bounded MVP check.
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

## Operations and product expansion

- Automated production cutover/rollback orchestration.
- Calibrated capacity, cost and SLO models based on production traffic.
- Full alert rules and external alert delivery.
- Analytics dashboards and richer operational summaries.
- Bulk editing, permanent deletion workflows and export/report features.

## Recording new deferred work

Append a short item under the matching section with:

- the behavior or risk;
- why it is not necessary for the current MVP packet;
- which packet or production evidence revealed it.

Do not use this file as permission to implement an item opportunistically.
