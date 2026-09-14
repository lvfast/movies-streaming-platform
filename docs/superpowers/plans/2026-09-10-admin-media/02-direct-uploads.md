# P2 — Direct Private Uploads

**Outcome:** An administrator can initiate, resume, complete or abort a private multipart video/artwork upload, and successful completion creates exactly one queued processing job without requiring RabbitMQ to be available.

**Depends on:** P1 DONE and `handoffs/P1.md` reviewed.

## Files

Create:

- `backend/src/main/resources/db/migration/V3__media_uploads_and_jobs.sql`
- `backend/src/main/java/com/lvfast/streaming/media/storage/MediaObjectStore.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/S3MediaObjectStore.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/StorageProperties.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/SignedPart.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/StoredPart.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/PartPage.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/CompletedPart.java`
- `backend/src/main/java/com/lvfast/streaming/media/storage/ObjectHead.java`
- `backend/src/main/java/com/lvfast/streaming/media/upload/UploadController.java`
- `backend/src/main/java/com/lvfast/streaming/media/upload/UploadService.java`
- `backend/src/main/java/com/lvfast/streaming/media/upload/JdbcUploadRepository.java`
- `backend/src/main/java/com/lvfast/streaming/media/upload/UploadPolicy.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/JdbcMediaJobRepository.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/JobView.java`
- `backend/src/main/java/com/lvfast/streaming/messaging/JdbcOutboxRepository.java`
- `backend/src/test/java/com/lvfast/streaming/media/MediaSchemaTest.java`
- `backend/src/test/java/com/lvfast/streaming/media/storage/S3MediaObjectStoreTest.java`
- `backend/src/test/java/com/lvfast/streaming/media/upload/DirectUploadHttpTest.java`
- `compose.media.local.yml`
- `docs/runbooks/media-local.md`

Modify:

- `backend/pom.xml`
- `backend/src/main/resources/application.yml`
- `.env.example`
- `.gitignore`
- `docs/api/openapi.yaml`
- `backend/src/test/java/com/lvfast/streaming/ArchitectureTest.java`

## Interfaces consumed and produced

Consume P1 current ADMIN authorization, movie lifecycle/revision and audit services.

Produce `MediaObjectStore` exactly as defined in [contracts.md](contracts.md) plus public records in the same storage package:

```java
record SignedPart(int partNumber, java.net.URI url, java.time.Instant expiresAt,
                  java.util.Map<String,String> headers) {}
record StoredPart(int partNumber, String etag, long sizeBytes) {}
record PartPage(java.util.List<StoredPart> items, Integer nextMarker) {}
record CompletedPart(int partNumber, String etag) {}
record ObjectHead(long sizeBytes, String etag, String contentType,
                  java.util.Map<String,String> metadata) {}
```

Admin upload endpoints and resource shapes follow `contracts.md`. Object keys are generated as `source/{movieId}/{uploadId}/original`; raw filenames never appear in keys.

## Acceptance criteria

- **P2-A:** V3 constrains media versions, assets, sessions, jobs, attempts, outbox and inbox records; a movie has at most one nonterminal video replacement.
- **P2-B:** The S3 adapter completes a real local multipart upload and supports list/head/abort/read/put/copy using separate internal and browser-signing endpoints.
- **P2-C:** Upload creation validates ADMIN, movie state, file kind/size/fingerprint and idempotency key, then returns an OPEN private session.
- **P2-D:** Part signing accepts 1-32 unique in-range part numbers. Resume lists authoritative uploaded parts.
- **P2-E:** Completion verifies ordered parts/ETags/sizes, marks COMPLETING, completes storage and transactionally creates one QUEUED job plus one outbox event.
- **P2-F:** Repeating completion after response loss returns the same session/job. A reserved object match requires session metadata and declared size.
- **P2-G:** Abort is idempotent for OPEN sessions; COMPLETING/COMPLETED sessions are not aborted. Expired OPEN sessions become EXPIRED and their multipart upload is aborted on a bounded sweep.

## Implementation sequence

- [ ] Add `MediaSchemaTest`, run `mvn -f backend/pom.xml --batch-mode --no-transfer-progress -Dtest=MediaSchemaTest test`, then implement V3 until only this test passes.
- [ ] Add the AWS SDK v2 S3 dependency using a pinned BOM and local storage overlay. Write `S3MediaObjectStoreTest` against the real emulator.
- [ ] Run `mvn -f backend/pom.xml --batch-mode --no-transfer-progress -Dtest=S3MediaObjectStoreTest test`; implement the adapter and rerun only this test.
- [ ] Add one `DirectUploadHttpTest` method for each P2-C through P2-G behavior. Implement creation, signing/listing, completion and abort in that order, running only the current method after each change.

Representative exactly-one assertion:

```java
assertThat(jdbc.queryForObject(
    "select count(*) from media_job where media_version_id=?",
    Integer.class, versionId)).isEqualTo(1);
assertThat(repeated.body().path("jobId").asText())
    .isEqualTo(completed.body().path("jobId").asText());
```

- [ ] Keep storage network calls outside database transactions. Persist COMPLETING before storage completion and reconcile the same request by `HeadObject`; do not add a general saga framework.
- [ ] Update OpenAPI and run `OpenApiContractTest` because upload/admin types changed.
- [ ] Validate Compose rendering once after the final overlay change: `docker compose --env-file .env.example -f compose.yml -f compose.local.yml -f compose.media.local.yml config --quiet`.
- [ ] Review exact storage prefixes and logs for credentials/signed URLs. Update ledger and write `handoffs/P2.md`. Stop; do not start P3.

## Packet completion evidence

Require fresh passing results for `MediaSchemaTest`, `S3MediaObjectStoreTest`, `DirectUploadHttpTest` and `OpenApiContractTest`, plus one successful Compose render. Do not run catalog/auth tests unless P2 changed their code after P1.

Commit explicit P2 paths with message `feat(admin-media): complete P2 direct uploads` when committing is authorized.
