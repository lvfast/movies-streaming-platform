# P5 — Publication and Protected Playback

**Outcome:** An administrator can attach validated artwork, preview, publish and activate a READY version; an authenticated viewer plays the active version through a token-protected media gateway and the existing HLS.js player.

**Depends on:** P4 DONE and `handoffs/P4.md` reviewed.

## Files

Create backend files:

- `backend/src/main/resources/db/migration/V4__media_playback_sessions.sql`
- `backend/src/main/java/com/lvfast/streaming/media/MediaCatalog.java`
- `backend/src/main/java/com/lvfast/streaming/administration/MoviePublicationService.java`
- `backend/src/main/java/com/lvfast/streaming/administration/AdminLifecycleController.java`
- `backend/src/main/java/com/lvfast/streaming/administration/ArtworkPromotionService.java`
- `backend/src/main/java/com/lvfast/streaming/playback/MediaSessionService.java`
- `backend/src/main/java/com/lvfast/streaming/playback/JdbcMediaSessionRepository.java`
- `backend/src/main/java/com/lvfast/streaming/playback/MediaTokenIssuer.java`
- `backend/src/main/java/com/lvfast/streaming/playback/MediaTokenController.java`
- `backend/src/main/java/com/lvfast/streaming/playback/AdminPreviewController.java`
- `backend/src/test/java/com/lvfast/streaming/administration/PublicationPlaybackTest.java`
- `backend/src/test/java/com/lvfast/streaming/playback/MediaTokenTest.java`

Create gateway files:

- `media-gateway/package.json` and lockfile
- `media-gateway/tsconfig.json`
- `media-gateway/wrangler.jsonc`
- `media-gateway/src/index.ts`
- `media-gateway/src/auth.ts`
- `media-gateway/src/media-path.ts`
- `media-gateway/src/delivery.ts`
- `media-gateway/src/cors.ts`
- `media-gateway/test/gateway.test.ts`
- `docs/runbooks/media-gateway.md`

Create frontend files:

- `frontend/src/playback/media-token-session.ts`
- `frontend/src/playback/private-hls.ts`
- `frontend/src/playback/media-token-session.test.ts`
- `frontend/src/playback/private-hls.test.ts`

Modify:

- `backend/src/main/java/com/lvfast/streaming/administration/JdbcAdminMovieRepository.java`
- `backend/src/main/java/com/lvfast/streaming/identity/SecurityConfiguration.java`
- `backend/src/main/java/com/lvfast/streaming/playback/Playback.java`
- `backend/src/main/java/com/lvfast/streaming/playback/PlaybackController.java`
- `backend/src/main/java/com/lvfast/streaming/playback/PlaybackRepository.java`
- `backend/src/main/java/com/lvfast/streaming/playback/PlaybackService.java`
- `backend/src/main/resources/application.yml`
- `docs/api/openapi.yaml`
- `frontend/src/api/streaming-api.ts`
- generated files under `frontend/src/api/generated/` through the generator only
- `frontend/src/pages/player-page.tsx`
- `frontend/src/test/fixtures.ts`
- `frontend/nginx.conf` to allow only the configured media origin in CSP

## Interfaces consumed and produced

Consume READY media/assets and the P3-P4 attempt output prefix. `MediaCatalog` exposes same-movie READY lookups without persistence entities:

```java
public interface MediaCatalog {
    ReadyMediaVersion readyVersion(java.util.UUID movieId, java.util.UUID versionId);
    ReadyMediaAsset readyAsset(java.util.UUID movieId, java.util.UUID assetId);
}
```

Produce publication/lifecycle, preview/playback/session-token endpoints and `PlaybackGrant` from [contracts.md](contracts.md). Frontend produces `createMediaTokenSession(...)` with the exact contract documented there.

## Acceptance criteria

- **P5-A:** Publish rejects incomplete metadata, non-READY/cross-movie media or artwork and leaves the previous active version untouched.
- **P5-B:** Successful publish promotes only validated artwork and atomically changes lifecycle, active version, runtime, public projection, movie/catalog revisions and audit.
- **P5-C:** Replacement READY does nothing until Activate; activation atomically changes the pointer while an existing playback session remains pinned to the old version.
- **P5-D:** Unpublish/Archive denies new sessions and token refresh; Restore returns UNPUBLISHED and never auto-publishes.
- **P5-E:** Media JWT uses the dedicated RS256 key/audience and binds session, movie, version, purpose and immutable prefix for at most five minutes.
- **P5-F:** Gateway rejects missing/login/wrong-prefix tokens before cache/R2 lookup and serves only allowlisted HLS GET/HEAD/single-range requests.
- **P5-G:** Anonymous access is limited to promoted `public-artwork/{assetId}/image.jpg`; no raw/source route exists.
- **P5-H:** Managed playback always uses HLS.js, adds media authorization only to the configured media origin, refreshes single-flight and retries authorization expiry once.
- **P5-I:** One local browser smoke decodes protected HLS; an unauthenticated request to the same manifest/segment returns 401.

## Implementation sequence

- [ ] Add `PublicationPlaybackTest` methods for publish rejection, atomic publish, replacement pinning and lifecycle denial. Implement one behavior at a time and run only its method.
- [ ] Add V4 sessions/progress, managed playback response and version-bound progress. Preserve the existing legacy response path and existing completion/resume semantics.
- [ ] Add `MediaTokenTest`, implement the isolated media signing configuration and refresh/preview authorization, then rerun only the token method being developed.

Representative binding assertion:

```java
assertThat(decoded.getAudience()).containsExactly("lvfast-media");
assertThat(decoded.getClaimAsString("versionId")).isEqualTo(versionId.toString());
assertThat(decoded.getClaimAsString("prefix")).endsWith(attemptId + "/");
```

- [ ] Create the gateway with one consolidated `gateway.test.ts` covering auth-before-read, canonical path binding, public artwork, GET/HEAD and one byte range. Run `npm.cmd --prefix media-gateway test -- gateway.test.ts` while implementing.
- [ ] Create token-session/private-HLS tests for single-flight refresh, one retry and foreign-origin rejection. Run `npm.cmd --prefix frontend test -- src/playback/media-token-session.test.ts src/playback/private-hls.test.ts`.
- [ ] Modify the existing player only after transport tests pass. Managed grants use HLS.js even when native HLS exists; legacy grants keep current behavior.
- [ ] Update OpenAPI, regenerate the frontend client once and run `OpenApiContractTest` plus the two playback transport tests because they consume the changed contract.
- [ ] Run one local browser smoke for authenticated decode and unauthenticated denial. Do not inspect unrelated public page styling.
- [ ] Review paths/tokens/logs, update ledger and write `handoffs/P5.md`. Stop; do not start P6.

## Packet completion evidence

Require fresh results for `PublicationPlaybackTest`, `MediaTokenTest`, `gateway.test.ts`, the two frontend playback tests, OpenAPI contract and the one local browser smoke. Run existing `PlaybackTestcontainersTest` only if its legacy code path changed after the focused tests.

Commit explicit P5 paths with message `feat(admin-media): complete P5 protected playback` when committing is authorized.
