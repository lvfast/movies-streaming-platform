# Admin and Private Media Design

Date: 2026-09-10  
Revised: 2026-09-12  
Status: approved design direction; implementation plan will be replaced with an MVP-oriented packet plan.

## Purpose

Add an administration workspace for creating and editing films, uploading source video and artwork, processing media, publishing a selected version, and monitoring the resulting jobs.

The system must be safe enough for private media and version replacement, but the first release must not spend implementation time on unlikely distributed-system edge cases or production-operations automation. The delivery plan therefore uses eight vertical execution packets instead of dozens of small horizontal tasks.

## Confirmed product requirements

- Product copy, validation/error text, code comments and project documents introduced by this feature use English.
- Administrators upload original video, poster and backdrop files directly to object storage.
- Production storage is Cloudflare R2. Source and delivery buckets are private. HLS is delivered through a Cloudflare Worker.
- Processing uses RabbitMQ and an independently deployable Java 21/Spring Boot FFmpeg worker in this repository.
- The API and worker do not share local disk. More than one API instance or worker must not corrupt job state.
- Produce one H.264/AAC HLS rendition, fit within 1920x1080, preserve display aspect ratio and do not upscale.
- Publishing is manual. A replacement video creates a new version, and activation atomically changes the active version.
- A failed upload or replacement must not affect the currently active version.
- Metadata changes take effect on save. Artwork changes take effect only after server-side validation.
- Roles are `USER` and `ADMIN`. The first administrator is granted by an operator command; registration never creates an administrator.
- Support Publish, Activate replacement, Unpublish, Archive and Restore. The Dashboard has no permanent movie or media deletion.
- The Admin SPA lives at `/admin` and includes library, editor, uploads, jobs, preview/publication and audit history.
- Public pages are not redesigned. The existing player only receives the private HLS transport required by managed media.

## MVP boundary

### Required for MVP

- Current database role checks for every admin request.
- Append-only audit records for role, editorial, publication and retry changes.
- Optimistic movie revision checks so concurrent editors cannot silently overwrite each other.
- Revision-keyed catalog caching so an admin save becomes visible across API instances without wildcard Redis scans.
- Private multipart uploads with backend-selected object keys, fresh part URLs and verified completion.
- An outbox-backed RabbitMQ command path so a committed upload does not lose its processing job during a broker outage.
- Conditional job claim with an attempt ID. Events from an old attempt cannot complete a newer attempt.
- At most three automatic attempts for transient processing failures.
- Server-side content probing and validation before a media version or artwork asset becomes READY.
- Atomic publication/activation and version-pinned playback sessions.
- A short-lived media JWT bound to movie, version and immutable HLS prefix.
- Authentication before every protected Worker storage/cache read, including byte-range requests.
- One complete Admin UI journey and one final integration gate.

### Deferred until post-MVP

- Exhaustive crash matrices covering every scheduler, broker-confirm and shutdown interleaving.
- A generic messaging framework intended for domains other than media.
- Separate rate limiters for every low-volume admin endpoint.
- Automated media cleanup execution. MVP may provide report-only orphan diagnostics.
- Automated backup/restore, production cutover or rollback orchestration.
- Full capacity modelling, cost modelling and production alert calibration.
- Automatic key-rotation orchestration and exhaustive rotation-overlap tests.
- Multiple browser inspections for each frontend component. Responsive, accessibility and browser checks are performed at the final UI packet and integration gate.
- Conditional HTTP/cache variants that are not required for ordinary HLS playback, beyond GET, HEAD and one valid byte range.
- Compliance claims such as tamper-proof audit storage.
- Adaptive bitrate ladders, subtitles, native HLS, mobile applications, TV applications and DRM.

Deferred work must not be implemented opportunistically inside an MVP packet. Record it in the post-MVP backlog instead.

## Core invariants

These invariants are not optional simplification targets:

1. A `USER` cannot call admin endpoints, even if a stale login token contains an ADMIN hint.
2. Browsers never receive object-storage credentials or choose arbitrary object keys.
3. Raw source objects and unvalidated artwork are never publicly readable.
4. The worker never connects directly to the application database.
5. A committed upload creates at most one logical processing job.
6. Only the current claimed attempt may change a job to a terminal state.
7. Only validated READY media and artwork may be published.
8. Replacement processing never changes the active version; activation is an explicit database transaction.
9. A media token authorizes exactly one immutable HLS prefix.
10. Authentication is evaluated before a Worker cache or R2 read.

## Architecture

| Component | Responsibility |
| --- | --- |
| Existing backend | Identity, admin APIs, movie lifecycle, upload sessions, durable job state, publication, audit and playback grants |
| PostgreSQL | Authoritative roles, catalog, media versions, assets, uploads, jobs, outbox/inbox records, playback sessions and audit |
| Redis | Revision-keyed public catalog cache; never an authorization source |
| Private source bucket | Original video and artwork uploads |
| RabbitMQ | Notification that a durable job is available; duplicates are allowed |
| Java transcoder | Claim jobs, download source, probe/encode/normalize, upload immutable output and emit progress/results |
| Private delivery bucket | Validated HLS and artwork objects |
| Cloudflare Worker | Validate media tokens, authorize canonical paths and stream delivery objects |
| Existing React SPA | Public player integration plus lazy-loaded `/admin` workspace |

Backend package ownership remains focused: `identity`, `administration`, `media`, `catalog`, `playback` and `audit`. Cross-package calls use small public facades. The transcoder and media gateway have independent builds and do not import backend persistence classes.

## Data model

Use additive Flyway migrations; never edit the applied V1 migration. Exact migration numbers are selected when the implementation packet starts so unrelated work cannot make the plan stale.

The MVP needs these records:

- `user_role` and `audit_event`.
- Movie management mode, lifecycle, revision, first-published timestamp, active media version, poster asset, backdrop asset and a singleton catalog revision.
- `media_version`, `media_asset` and `upload_session`.
- `media_job`, `media_job_attempt`, `outbox_event` and `inbox_event`.
- `media_playback_session` and version-specific viewing progress.

Use UUID identifiers and UTC timestamps. Database constraints enforce same-movie version/asset references and only one nonterminal replacement video per movie. Completed source and successful output are retained in MVP.

## Lifecycle

```text
Movie: DRAFT -> PUBLISHED -> UNPUBLISHED -> PUBLISHED
       DRAFT/PUBLISHED/UNPUBLISHED -> ARCHIVED -> UNPUBLISHED

Upload: OPEN -> COMPLETING -> COMPLETED
        OPEN -> ABORTED | EXPIRED
        COMPLETING -> FAILED when completion cannot be reconciled

Media version: UPLOADING -> QUEUED -> PROCESSING -> READY
               UPLOADING -> ABORTED
               QUEUED/PROCESSING -> FAILED

Job: QUEUED -> RUNNING -> SUCCEEDED
     RUNNING -> RETRY_WAIT -> QUEUED
     RUNNING -> FAILED
```

Movie revision changes only for editorial or lifecycle mutations, not for worker progress. `If-Match` is required for update, artwork attachment, publication, activation, unpublish, archive and restore. Idempotency keys are required only where response loss could otherwise duplicate a durable resource or command: movie creation, upload creation/completion, publication/activation and manual retry.

## Upload flow

1. The API validates the administrator, movie state, file kind and configured size limit, then creates a private upload session and backend-owned key.
2. The API initiates multipart upload outside a database transaction and returns an OPEN session. A repeated create request with the same idempotency key returns the same session.
3. The browser requests part URLs in bounded batches and uploads with credentials omitted. URLs and storage credentials are never persisted in the browser.
4. Resume requires reselecting a file whose bounded first/last-block fingerprint matches the session. Uploaded parts come from storage `ListParts`, not browser memory.
5. Complete verifies the authoritative part list and moves the session to COMPLETING before calling storage. A repeated completion checks the reserved object and finishes the same durable job transition.
6. The final database transaction marks the upload COMPLETED, creates one queued job and appends one outbox event.

MVP does not require a general background saga engine. Recovery for uncertain completion is driven by repeating the completion request plus a small bounded startup/scheduled check for stuck COMPLETING sessions if implementation evidence shows it is necessary.

## Processing flow

1. An outbox publisher sends a persistent media command after the upload transaction commits.
2. A worker receives a command and calls the private backend claim endpoint. The database conditionally creates an attempt ID and lease.
3. The worker acknowledges the RabbitMQ command after the durable claim, processes one job in its bounded slot and heartbeats the lease.
4. Every attempt writes to a unique output prefix. If the lease is lost, the worker stops; late events are ignored by attempt ID.
5. The worker probes actual content, validates configured limits and produces either one H.264/AAC HLS rendition or one normalized JPEG.
6. It uploads immutable objects, writes `artifact.json` last and publishes a result event.
7. The backend deduplicates the event, verifies artifact identity/object coverage and marks the job and target READY only for the current attempt.

Retry only transient storage, broker or process-timeout failures. Invalid/unsupported source and invalid output are terminal. Use at most three total attempts with simple configured delays.

The MVP recovery integration gate covers exactly three high-value cases:

- worker death after durable claim;
- duplicate or late result event;
- response loss after object-storage completion.

Additional interleavings belong in post-MVP unless a real defect demonstrates the need for them.

## Media processing profile

- Accepted video containers: MP4, MOV and Matroska.
- Maximum source: configurable, initially 20 GiB and six hours.
- Require one video and one audio track; select the default audio track or the first audio track.
- Reject malformed media, missing tracks, HDR and decoded bounds above the configured 4K/60 fps input ceiling.
- Output software H.264, yuv420p, AAC stereo, at most 1920x1080, at most 30 fps, no upscaling and approximately six-second MPEG-TS segments.
- JPEG/PNG artwork input only, initially limited to 10 MiB and 24 megapixels.
- Normalize orientation, remove metadata and encode poster 600x900 or backdrop 1600x900 JPEG.

FFmpeg/ffprobe use `ProcessBuilder` arguments, never shell interpolation or a client-supplied URL. Source paths and process output are bounded. Real short synthetic fixtures prove probe, encode and decode behavior once in the processing packet.

## Publication and playback

Publishing requires valid metadata, at least one genre, READY poster/backdrop assets and a READY selected media version. Publication copies only validated artwork into the public artwork namespace, then atomically sets lifecycle, active version, runtime, artwork pointers, compatibility projection, catalog revision and audit record.

Replacement READY does not alter publication. Activation atomically changes only the active version and related runtime/manifest projection. Existing playback sessions remain pinned to their original READY version; new sessions use the new active version.

Managed playback returns a version-pinned session, manifest URL, media token and expiry. The token uses a signing key distinct from login tokens and binds subject, session, movie, version, purpose and slash-terminated immutable prefix. Unpublish or Archive denies new sessions and token refresh; an already issued short-lived token may remain valid until expiry.

The media gateway:

- accepts only allowlisted HLS paths, public sanitized artwork paths and configured web origins;
- validates RS256 signature, issuer, audience, expiry and path-binding claims before reading cache/R2;
- supports GET, HEAD and a single byte range needed by playback;
- never accepts login tokens, query-string bearer tokens, raw bucket keys or source paths;
- streams bodies without buffering entire media objects.

Managed playback always uses HLS.js so an Authorization header is added to manifest and segment requests. Token refresh is single-flight and retried once on authorization expiry. Legacy local fixtures may keep their existing playback path until managed cutover.

## Admin experience

The lazy `/admin` bundle provides:

- an ADMIN route guard and film library;
- draft creation and metadata editing with explicit Save actions;
- video/poster/backdrop upload and resume;
- job status and manual retry;
- READY media preview, artwork selection, publish and replacement activation;
- Unpublish, Archive and Restore controls;
- a simple audit history view.

The UI clearly separates upload progress, processing state, media readiness and movie lifecycle. It contains no delete or role-management screen. Final visual tokens are chosen immediately before the Admin UI packets; they do not block backend work.

## Eight execution packets

Each packet is a reviewable vertical slice with one handoff. Fine-grained acceptance IDs may be used inside a packet, but they are not separate sessions.

| Packet | Outcome |
| --- | --- |
| P1 Admin foundation | Operator can grant ADMIN; authenticated admin can create/edit drafts safely; seed/cache behavior preserves edits |
| P2 Direct upload | Admin can create, resume, complete or abort private multipart video/artwork uploads and obtains one queued job |
| P3 Processing happy path | RabbitMQ worker converts a small authorized fixture into verified READY HLS/artwork |
| P4 Processing reliability | Lease expiry, bounded retry, duplicate/late events and the three required recovery cases behave correctly |
| P5 Publish and playback | Admin publishes/activates safely; viewer plays protected HLS through the gateway and existing player |
| P6 Admin core UI | Protected Admin shell, library and draft editor work against generated backend contracts |
| P7 Admin media UI | Upload, jobs, preview, publish/lifecycle and audit journeys are usable |
| P8 Integration and release | One complete local journey passes; provider-specific checks are recorded without pretending unavailable cloud evidence passed |

### Packet definition of done

A packet is complete only when:

- its listed behavior and core invariants are implemented;
- tests directly related to changed code pass;
- the diff contains no unrelated changes or secrets;
- public interfaces changed by the packet are reflected in the checked-in contract;
- one concise handoff records changed files, test commands/results, known limitations and the next packet;
- deferred cases discovered during work are written to the post-MVP backlog instead of silently expanding the packet.

## Focused testing and failure-loop policy

The implementation workflow optimizes for fast feedback:

1. Develop one coherent behavior at a time with a focused failing test, then the smallest implementation that makes it pass.
2. After a behavior changes, run only its focused test and directly affected neighboring tests.
3. If a test fails unexpectedly, inspect the failure and identify a likely cause before editing code. Do not repeatedly change code without a hypothesis.
4. Rerun only the failed or affected test after a fix. Do not rerun an unrelated module or the complete suite.
5. After two unsuccessful fix attempts for the same failure, stop the loop and report:
   - the exact test command;
   - a short error summary;
   - the suspected cause;
   - the two approaches already tried.
6. When the packet's directly relevant tests pass, end the implementation session and report the result. Do not start the next packet automatically.
7. Run the full repository suite at most once at P8 when final integration evidence requires it. Expensive Compose, browser and provider checks run only in their owning packet.

Test failures caused by an actual change in a shared contract are directly affected and must be run. A vague possibility of regression is not enough reason to execute the entire suite.

## Success criteria

The MVP is successful when an operator can grant an administrator; the administrator can create a film, upload and process video/artwork, preview and publish it; an authenticated viewer can play token-protected HLS; a replacement can be processed and activated without disturbing the old active version; and the Admin UI exposes the required operational states without a delete path.

Production deployment, account provisioning and claims of provider readiness remain separate authorized operational actions.
