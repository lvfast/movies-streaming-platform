# P4 — Minimum Processing Reliability

**Outcome:** The processing path recovers from the three failure modes most likely to lose or corrupt work, without implementing an exhaustive distributed-systems test matrix.

**Depends on:** P3 DONE and `handoffs/P3.md` reviewed.

## Files

Create:

- `backend/src/main/java/com/lvfast/streaming/media/job/JobRecoveryScheduler.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/JobRetryPolicy.java`
- `backend/src/main/java/com/lvfast/streaming/media/job/AdminJobController.java`
- `backend/src/test/java/com/lvfast/streaming/media/job/ProcessingReliabilityTest.java`
- `tests/media-pipeline/compose.test.yml`
- `tests/media-pipeline/test_recovery.py`
- `tests/media-pipeline/README.md`

Modify:

- backend job/attempt/outbox/result repositories introduced by P2-P3
- `backend/src/main/java/com/lvfast/streaming/media/upload/UploadService.java`
- `transcoder/src/main/java/com/lvfast/transcoder/jobs/LeaseGuard.java`
- `transcoder/src/main/java/com/lvfast/transcoder/jobs/JobExecutor.java`
- `docs/api/openapi.yaml`

## Interfaces consumed and produced

Consume P3 attempt leases, result events and immutable output prefixes.

```java
public interface JobRetryPolicy {
    java.util.Optional<java.time.Duration> delayFor(int failedAttemptNumber,
                                                     String failureCode);
}
```

`delayFor(1, transient)` returns 60 seconds, `delayFor(2, transient)` returns 300 seconds, and the third failed attempt or a permanent error returns empty. Manual retry creates a new job ID targeting the retained source.

## Acceptance criteria

- **P4-A:** An expired RUNNING lease closes that attempt and moves a transient job to RETRY_WAIT or terminal FAILED according to the policy.
- **P4-B:** A due RETRY_WAIT job becomes QUEUED with one new outbox command and a later claim receives a new attempt/output prefix.
- **P4-C:** Duplicate result event IDs do not apply twice; lower sequence or old-attempt events cannot regress current/terminal state.
- **P4-D:** Worker death after durable claim is recovered by lease expiry and a second worker reaches READY.
- **P4-E:** Response loss after multipart completion creates exactly one logical job when the completion request is repeated.
- **P4-F:** A duplicate/late completion-progress sequence leaves one accepted terminal transition.
- **P4-G:** Admin may retry a terminal failed job once per idempotency key and receives the new job ID.

No other crash interleaving is required for this packet. Add newly imagined cases to `post-MVP.md` unless an observed defect blocks P4-A through P4-G.

## Implementation sequence

- [ ] Add `ProcessingReliabilityTest` for retry delays, expired lease and old-attempt fencing. Run each method separately while implementing scheduler/policy changes.

```java
assertThat(policy.delayFor(1, "STORAGE_UNAVAILABLE"))
    .contains(java.time.Duration.ofSeconds(60));
assertThat(policy.delayFor(2, "STORAGE_UNAVAILABLE"))
    .contains(java.time.Duration.ofSeconds(300));
assertThat(policy.delayFor(3, "STORAGE_UNAVAILABLE")).isEmpty();
assertThat(policy.delayFor(1, "SOURCE_INVALID")).isEmpty();
```

- [ ] Add manual retry endpoint behavior and OpenAPI shape. Run only its HTTP method plus `OpenApiContractTest` after the contract change.
- [ ] Add the disposable Compose recovery harness with explicit project name and bounded deadlines. It may control only resources it created.
- [ ] Implement exactly three integration methods in `test_recovery.py`: `test_worker_dies_after_claim`, `test_duplicate_and_late_results`, and `test_upload_completion_response_loss`.
- [ ] Run each method separately while developing: `python tests/media-pipeline/test_recovery.py MediaRecoveryTest.test_worker_dies_after_claim -v`, then replace only the final method name with `test_duplicate_and_late_results` or `test_upload_completion_response_loss`.
- [ ] After all three have passed and no recovery code changed afterward, run the file once: `python tests/media-pipeline/test_recovery.py -v`.
- [ ] Sanitize retained evidence so it contains IDs/states/durations but no credentials, JWTs, signed URLs or source bytes.
- [ ] Update ledger and write `handoffs/P4.md`. Stop; do not start P5.

## Two-attempt stop rule

The session guide applies independently to each failing unit/integration method. After two unsuccessful code/config fixes for the same scenario, stop P4 and report the exact command, short failure, suspected cause and both attempted fixes. Do not add retries, longer sleeps or broader suites merely to make a flaky scenario green.

## Packet completion evidence

Require `ProcessingReliabilityTest`, the manual retry contract method and the three named recovery scenarios. Do not rerun P3's full happy-path set unless a P4 edit changed the worker encoder/artifact path.

Commit explicit P4 paths with message `feat(admin-media): complete P4 processing reliability` when committing is authorized.
