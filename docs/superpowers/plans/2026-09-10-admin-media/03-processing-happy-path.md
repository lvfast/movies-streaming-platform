# P3 — Processing Happy Path

**Outcome:** A completed upload is published through RabbitMQ, claimed by an independent worker, converted into validated immutable HLS or artwork, and exposed as READY through admin APIs.

**Depends on:** P2 DONE and `handoffs/P2.md` reviewed.

## Files

Create backend files:

- `backend/src/main/java/com/lvfast/streaming/messaging/OutboxPublisher.java`
- `backend/src/main/java/com/lvfast/streaming/messaging/RabbitTopology.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/WorkerJobController.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/JobClaimService.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/WorkerCredentialFilter.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/MediaResultConsumer.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/MediaResultService.java`
- `backend/src/main/java/com/lvfast/streaming/media/artifact/StoredArtifactVerifier.java`
- `backend/src/main/java/com/lvfast/streaming/media/AdminMediaController.java`
- `backend/src/test/java/com/lvfast/streaming/media/ProcessingHappyPathTest.java`
- `backend/src/test/java/com/lvfast/streaming/media/ArtifactVerifierTest.java`
- `contracts/media/command-v1.schema.json`
- `contracts/media/event-v1.schema.json`
- `contracts/media/artifact-v1.schema.json`
- `contracts/media/fixtures/command-valid.json`
- `contracts/media/fixtures/command-invalid.json`
- `contracts/media/fixtures/event-valid.json`
- `contracts/media/fixtures/event-invalid.json`
- `contracts/media/fixtures/artifact-valid.json`
- `contracts/media/fixtures/artifact-invalid.json`

Create worker files:

- `transcoder/pom.xml`
- `transcoder/Dockerfile`
- `transcoder/src/main/resources/application.yml`
- `transcoder/src/main/java/com/lvfast/transcoder/TranscoderApplication.java`
- `transcoder/src/main/java/com/lvfast/transcoder/jobs/CommandConsumer.java`
- `transcoder/src/main/java/com/lvfast/transcoder/jobs/BackendJobClient.java`
- `transcoder/src/main/java/com/lvfast/transcoder/jobs/LeaseGuard.java`
- `transcoder/src/main/java/com/lvfast/transcoder/jobs/JobExecutor.java`
- `transcoder/src/main/java/com/lvfast/transcoder/storage/WorkerObjectStore.java`
- `transcoder/src/main/java/com/lvfast/transcoder/process/BoundedProcessRunner.java`
- `transcoder/src/main/java/com/lvfast/transcoder/probe/VideoProbe.java`
- `transcoder/src/main/java/com/lvfast/transcoder/probe/VideoDimensions.java`
- `transcoder/src/main/java/com/lvfast/transcoder/encode/HlsEncoder.java`
- `transcoder/src/main/java/com/lvfast/transcoder/artwork/ArtworkProcessor.java`
- `transcoder/src/main/java/com/lvfast/transcoder/output/ArtifactWriter.java`
- `transcoder/src/main/java/com/lvfast/transcoder/events/MediaEventPublisher.java`
- focused tests mirroring those packages under `transcoder/src/test/java/com/lvfast/transcoder/`

Modify `backend/pom.xml`, backend configuration/security, `compose.media.local.yml`, `.gitignore`, `docs/api/openapi.yaml` and `backend/src/test/java/com/lvfast/streaming/ArchitectureTest.java`.

## Interfaces consumed and produced

Consume P2 queued job/outbox records and `MediaObjectStore`. Produce the internal claim/heartbeat endpoints, versioned command/result/artifact schemas and admin list/get APIs defined in [contracts.md](contracts.md).

Worker execution contract:

```java
public interface JobExecutor {
    void execute(ClaimResult claim, LeaseGuard lease);
}

public record Dimensions(int width, int height) {}
```

The Rabbit command carries a job ID only. The authenticated claim response supplies source key, expected bytes, output prefix and processing profile.

## Acceptance criteria

- **P3-A:** Outbox rows are marked published only after Rabbit publisher confirmation; broker outage leaves them pending.
- **P3-B:** A worker command is acknowledged only after the backend durably returns CLAIMED or terminal SKIP. Default consumer concurrency/prefetch is one.
- **P3-C:** Claim creates a unique attempt ID, lease and output prefix. Worker machine authentication is distinct from human JWT authentication.
- **P3-D:** Video probing validates actual bytes/tracks/duration/dimensions and selects output geometry without upscaling.
- **P3-E:** A short synthetic video becomes H.264/AAC VOD HLS and decodes successfully.
- **P3-F:** JPEG/PNG artwork is decoded, orientation-normalized, center-cropped, metadata-stripped and encoded to the required JPEG dimensions.
- **P3-G:** Worker uploads output objects before `artifact.json`; backend verifies identity, prefix, playlist coverage and object sizes before READY.
- **P3-H:** Admin can list movie versions/assets and list/get jobs without receiving raw source keys, credentials or signed URLs.

## Implementation sequence

- [ ] Add JSON schemas/fixtures and the `ProcessingHappyPathTest` methods `publishesOnlyAfterBrokerConfirmation`, `claimsWithMachineIdentity` and `acceptsCurrentAttemptResult`. Run each with `-Dtest=ProcessingHappyPathTest#publishesOnlyAfterBrokerConfirmation` and the corresponding exact method name while implementing it.
- [ ] Add Rabbit dependencies/topology and implement confirmed outbox publishing. Verify the broker-down and confirmed-delivery methods before continuing.
- [ ] Implement worker security, claim/heartbeat and inbox-deduplicated result handling. Completion remains rejected until `StoredArtifactVerifier` succeeds.
- [ ] Create the independent worker with no JDBC/JPA dependency. Add `CommandConsumerTest` proving `claim -> ACK -> execute` ordering and maximum concurrency one.
- [ ] Add pure `VideoDimensionsTest`, real `VideoProbeTest` and `HlsEncoderTest`. Generate short test media from FFmpeg sources in the test workspace; do not check in large binaries.

Representative dimension behavior:

```java
assertThat(VideoDimensions.fit(3840, 2160))
    .isEqualTo(new Dimensions(1920, 1080));
assertThat(VideoDimensions.fit(640, 360))
    .isEqualTo(new Dimensions(640, 360));
```

- [ ] Add `ArtworkProcessorTest` for 600x900 poster and 1600x900 backdrop, plus corrupt/oversized input rejection.
- [ ] Implement immutable artifact upload and backend verification. Run `ArtifactVerifierTest` for missing segment, wrong prefix and valid artifact.
- [ ] Wire admin version/asset/job read endpoints and update OpenAPI; run `OpenApiContractTest`.
- [ ] Run the local happy-path scenario once: synthetic upload -> Rabbit -> worker -> READY HLS and artwork. Decode the generated HLS as the assertion; do not add recovery failures yet.
- [ ] Review the worker dependency tree to confirm no database driver/JPA dependency. Update ledger and write `handoffs/P3.md`. Stop; do not start P4.

## Packet completion evidence

Require fresh focused results for backend `ProcessingHappyPathTest,ArtifactVerifierTest`, worker `CommandConsumerTest,VideoProbeTest,VideoDimensionsTest,HlsEncoderTest,ArtworkProcessorTest`, OpenAPI contract and the single local happy-path scenario. Previously passing focused tests need rerun only if later P3 edits touched their execution path.

Commit explicit P3 paths with message `feat(admin-media): complete P3 processing happy path` when committing is authorized.
