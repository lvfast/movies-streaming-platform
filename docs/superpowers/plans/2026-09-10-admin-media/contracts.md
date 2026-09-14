# Admin Media MVP Contracts

This file is the shared naming and wire-contract reference for P1-P8. A packet that changes a public name or shape updates this file and `docs/api/openapi.yaml` in the same session.

## HTTP conventions

- Public/admin prefix: `/api/v1`.
- Internal worker prefix: `/internal/v1`; public nginx must not proxy it.
- IDs are UUID strings and timestamps are RFC 3339 UTC.
- Collection responses use `{items,page,size,total}` with zero-based pages, default size 20 and maximum 100.
- Admin and token responses use `Cache-Control: no-store`.
- Individual movie responses return `ETag: "<revision>"`.
- Movie update/lifecycle commands require `If-Match`.
- Movie creation, upload creation/completion, publish/activate and manual retry require `Idempotency-Key`.
- Problems retain the existing RFC 9457 response and English `code`/`detail` fields.

Use status 400 for malformed input, 401 for missing/invalid authentication, 403 for insufficient/currently revoked role, 404 for missing resources, 409 for invalid transitions or idempotency conflicts, 412 for stale revisions, 413 for declared size limits, 428 for missing `If-Match` and 503 for unavailable required infrastructure.

## Shared resource types

```typescript
type MovieLifecycle = 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | 'ARCHIVED';
type MediaState = 'UPLOADING' | 'QUEUED' | 'PROCESSING' | 'READY' | 'FAILED' | 'ABORTED';
type AssetState = 'UPLOADING' | 'STORED' | 'PROCESSING' | 'READY' | 'FAILED' | 'ABORTED';
type UploadKind = 'VIDEO' | 'POSTER' | 'BACKDROP';
type UploadState = 'OPEN' | 'COMPLETING' | 'COMPLETED' | 'ABORTED' | 'EXPIRED' | 'FAILED';
type JobState = 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED';

type MovieInput = {
  title: string;
  slug: string;
  synopsis: string;
  releaseYear: number;
  maturityRating: 'G' | 'PG' | 'PG-13' | 'R' | 'NC-17' | 'NR';
  genreIds: number[];
  featured: boolean;
};

type AdminMovie = MovieInput & {
  id: string;
  lifecycle: MovieLifecycle;
  revision: number;
  managementMode: 'LEGACY' | 'MANAGED';
  activeMediaVersionId: string | null;
  posterAssetId: string | null;
  backdropAssetId: string | null;
  runtimeSeconds: number | null;
  firstPublishedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

type UploadInput = {
  kind: UploadKind;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  resumeFingerprint: string;
};

type UploadSession = {
  id: string;
  movieId: string;
  mediaVersionId: string | null;
  assetId: string;
  kind: UploadKind;
  state: UploadState;
  partSizeBytes: number;
  totalParts: number;
  declaredBytes: number;
  expiresAt: string;
  jobId: string | null;
};

type UploadedPart = { partNumber: number; etag: string; sizeBytes: number };
type SignedPart = {
  partNumber: number;
  url: string;
  expiresAt: string;
  headers: Record<string, string>;
};

type JobView = {
  id: string;
  movieId: string;
  mediaVersionId: string | null;
  assetId: string | null;
  kind: 'TRANSCODE' | 'ARTWORK';
  state: JobState;
  attemptNumber: number;
  progressPercent: number;
  stage: string;
  errorCode: string | null;
  errorSummary: string | null;
  retryAt: string | null;
  updatedAt: string;
};

type PlaybackGrant = {
  movieId: string;
  manifestUrl: string;
  resumePositionSeconds: number;
  sessionId: string;
  mediaVersionId: string;
  mediaToken: string;
  mediaTokenExpiresAt: string;
};
```

Movie validation: trimmed title 1-200 characters, slug 1-120 lowercase letters/digits separated by single hyphens, synopsis 0-10,000 in draft and at least one character to publish, release year 1888-2200, no duplicate genre IDs and at most ten genres. Publishing requires at least one genre. Runtime and media/artwork URLs are server-owned.

`resumeFingerprint` is `sha256:` plus 64 lowercase hexadecimal characters. The browser hashes UTF-8 decimal file size plus newline, the first up-to-1-MiB block and the last up-to-1-MiB block. It is a resume guard, not authoritative content validation.

## Admin endpoints

| Method/path | Operation ID | Success |
| --- | --- | --- |
| GET `/admin/movies` | `adminListMovies` | `AdminMovie` page |
| POST `/admin/movies` | `adminCreateMovie` | 201 `AdminMovie` + ETag |
| GET `/admin/movies/{movieId}` | `adminGetMovie` | `AdminMovie` + ETag |
| PUT `/admin/movies/{movieId}` | `adminUpdateMovie` | `AdminMovie` + ETag |
| GET `/admin/genres` | `adminListGenres` | `{items:[{id,slug,name}]}` |
| POST `/admin/movies/{movieId}/uploads` | `adminCreateUpload` | 201 `UploadSession` |
| GET `/admin/uploads/{uploadId}` | `adminGetUpload` | `UploadSession` |
| GET `/admin/uploads/{uploadId}/parts` | `adminListUploadParts` | paged `UploadedPart` items |
| POST `/admin/uploads/{uploadId}/part-urls` | `adminSignUploadParts` | `{items:SignedPart[]}`; at most 32 parts |
| POST `/admin/uploads/{uploadId}/complete` | `adminCompleteUpload` | `UploadSession` with `jobId` |
| POST `/admin/uploads/{uploadId}/abort` | `adminAbortUpload` | `UploadSession` |
| GET `/admin/movies/{movieId}/versions` | `adminListVersions` | movie-scoped media versions |
| GET `/admin/movies/{movieId}/assets` | `adminListAssets` | movie-scoped artwork assets |
| POST `/admin/assets/{assetId}/preview` | `adminPreviewAsset` | 60-second private `{url,expiresAt}` |
| POST `/admin/movies/{movieId}/artwork` | `adminAttachArtwork` | `AdminMovie` + ETag |
| GET `/admin/jobs` | `adminListJobs` | `JobView` page |
| GET `/admin/jobs/{jobId}` | `adminGetJob` | `JobView` |
| POST `/admin/jobs/{jobId}/retry` | `adminRetryJob` | 201 new `JobView` |
| POST `/admin/movies/{movieId}/preview` | `adminPreviewMovie` | preview `PlaybackGrant` |
| POST `/admin/movies/{movieId}/publish` | `adminPublishMovie` | `AdminMovie` + ETag |
| POST `/admin/movies/{movieId}/activate` | `adminActivateVersion` | `AdminMovie` + ETag |
| POST `/admin/movies/{movieId}/unpublish` | `adminUnpublishMovie` | `AdminMovie` + ETag |
| POST `/admin/movies/{movieId}/archive` | `adminArchiveMovie` | `AdminMovie` + ETag |
| POST `/admin/movies/{movieId}/restore` | `adminRestoreMovie` | UNPUBLISHED `AdminMovie` + ETag |
| GET `/admin/audit` | `adminListAudit` | paged audit events |

`POST /auth/*` and `GET /auth/me` user shapes add `roles: string[]`. Registration rejects role input. Role mutations are operator-command only.

## Playback endpoints

- `GET /movies/{movieId}/playback`: existing entry point; managed movies return `PlaybackGrant`, legacy fixtures retain the existing response shape during migration.
- `POST /me/playback-sessions/{sessionId}/token`: same authenticated user, unexpired session and published movie; returns `{mediaToken,mediaTokenExpiresAt}`.
- `PUT /me/progress/{movieId}`: managed body includes `sessionId` and `mediaVersionId`; the session/user/movie/version must match.
- `POST /admin/playback-sessions/{sessionId}/token`: current ADMIN refreshes their own READY preview session.

## Internal worker endpoints

```text
POST /internal/v1/jobs/{jobId}/claim
Authorization: configured machine credential
Body: {workerId}
Response: {disposition:"CLAIMED",attemptId,leaseUntil,kind,source,
           outputPrefix,movieId,mediaVersionId,assetId,profile}
      or {disposition:"SKIP",reason:"NOT_AVAILABLE"|"TERMINAL"}

POST /internal/v1/jobs/{jobId}/heartbeat
Body: {workerId,attemptId}
Response: {leaseUntil} or 409 LEASE_LOST
```

The credential binds the allowed server-side worker identity. Human JWTs never satisfy internal endpoint authentication. Claim responses, not RabbitMQ messages, provide immutable source/output parameters.

## Message and artifact contracts

Command messages contain only `schemaVersion`, `eventId`, `type`, `jobId` and `occurredAt`. Supported command types are `transcode.requested.v1` and `artwork.requested.v1`.

Result messages contain `schemaVersion`, `eventId`, `type`, `jobId`, `attemptId`, monotonic `sequence`, `occurredAt` and `payload`. Supported types are:

- `media.progress.v1`: `{stage,percent}`;
- `media.completed.v1`: `{artifactKey}`;
- `media.failed.v1`: `{code,summary}`.

Stages are `DOWNLOADING`, `PROBING`, `ENCODING`, `UPLOADING` and `VALIDATING`. Failure codes are `SOURCE_INVALID`, `SOURCE_UNSUPPORTED`, `STORAGE_UNAVAILABLE`, `PROCESS_TIMEOUT`, `DISK_EXHAUSTED`, `LEASE_LOST`, `OUTPUT_INVALID` and `INTERNAL_ERROR`. Only storage unavailability and process timeout automatically retry in MVP.

```typescript
type Artifact = {
  schemaVersion: 1;
  jobId: string;
  attemptId: string;
  kind: 'TRANSCODE' | 'ARTWORK';
  profile: string;
  movieId: string;
  mediaVersionId: string | null;
  assetId: string | null;
  width: number;
  height: number;
  durationSeconds: number | null;
  videoCodec: 'h264' | null;
  audioCodec: 'aac' | null;
  playlist: string | null;
  objects: Array<{key:string;sizeBytes:number;sha256:string}>;
};
```

Artifact keys are relative to the backend-issued attempt prefix. Transcode artifacts require positive duration, `h264`, `aac`, `index.m3u8` and every referenced segment. Artwork artifacts require exactly one `image.jpg`. The worker uploads output objects first and `artifact.json` last.

## Core implementation interfaces

```java
public interface MediaObjectStore {
    String initiate(String bucketRole, String key, String contentType,
                    java.util.Map<String,String> metadata);
    SignedPart signPart(String bucketRole, String key, String uploadId,
                        int partNumber, java.time.Duration ttl);
    PartPage listParts(String bucketRole, String key, String uploadId, Integer marker);
    void complete(String bucketRole, String key, String uploadId,
                  java.util.List<CompletedPart> parts);
    ObjectHead head(String bucketRole, String key);
    void abort(String bucketRole, String key, String uploadId);
    java.io.InputStream read(String bucketRole, String key);
    void put(String bucketRole, String key, java.nio.file.Path file, String contentType);
    void copy(String bucketRole, String fromKey, String toKey);
}
```

The backend records for this interface live in `com.lvfast.streaming.media.storage`. The transcoder defines equivalent records in its own module and does not import backend code.

```typescript
interface MediaTokenSession {
  getToken(): Promise<string>;
  invalidate(): void;
  dispose(): void;
}

function createMediaTokenSession(
  initial: PlaybackGrant,
  renew: () => Promise<{mediaToken:string;mediaTokenExpiresAt:string}>,
  clock: () => number,
): MediaTokenSession;
```

## Media JWT

Header: `{alg:"RS256",typ:"JWT",kid:string}`. Required claims: `iss`, `aud:"lvfast-media"`, `sub`, `sid`, `movieId`, `versionId`, `prefix`, `purpose`, `iat`, `nbf`, `exp` and `jti`. The prefix begins `/hls/`, ends `/` and contains the same canonical movie/version/attempt UUIDs as the requested path.

The gateway authenticates before cache/R2 access. It accepts bearer headers only and serves protected HLS GET/HEAD plus one valid byte range. Public artwork is restricted to `public-artwork/{assetId}/image.jpg` generated from validated output.
