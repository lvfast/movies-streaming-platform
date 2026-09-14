# P7 — Admin Media Operations UI

**Outcome:** Administrators can upload/resume media, monitor/retry processing, preview READY versions, publish/activate/lifecycle films and inspect audit history from the Admin workspace.

**Depends on:** P6 DONE and `handoffs/P6.md` reviewed.

## Files

Create:

- `frontend/src/admin/uploads/upload-fingerprint.ts`
- `frontend/src/admin/uploads/r2-part-uploader.ts`
- `frontend/src/admin/uploads/upload-manager.ts`
- `frontend/src/admin/uploads/upload-page.tsx`
- `frontend/src/admin/jobs/jobs-page.tsx`
- `frontend/src/admin/jobs/use-job-polling.ts`
- `frontend/src/admin/review/movie-review-page.tsx`
- `frontend/src/admin/review/preview-player.tsx`
- `frontend/src/admin/review/publication-actions.tsx`
- `frontend/src/admin/audit/audit-page.tsx`
- `frontend/src/admin/admin-media.test.tsx`
- `frontend/src/admin/uploads/upload-manager.test.ts`

Modify:

- `frontend/src/api/admin-api.ts`
- `frontend/src/admin/admin-routes.tsx`
- `frontend/src/admin/admin.css`
- `frontend/src/test/fixtures.ts`
- `docs/superpowers/specs/2026-09-10-admin-visual-design.md` only if implemented states require a missing token/component rule

## Interfaces consumed and produced

Extend P6 `AdminApi` with the upload, version/asset, job, preview, artwork, publication/lifecycle and audit operations from [contracts.md](contracts.md).

```typescript
interface UploadManager {
  start(file: File, session: UploadSession): Promise<UploadSession>;
  resume(file: File, session: UploadSession): Promise<UploadSession>;
  pause(): void;
  abort(): Promise<UploadSession>;
}
```

Persist only upload ID, movie ID, fingerprint and non-sensitive local file metadata. Never persist a `File`, login/media token, storage credential or presigned URL. Storage requests use `credentials: 'omit'` and only the signed headers.

## Acceptance criteria

- **P7-A:** Browser fingerprint reads bounded first/last chunks; upload sends at most four parts concurrently and skips parts confirmed by backend `ListParts`.
- **P7-B:** Expired part URLs are refreshed through the backend with bounded retry. Completion remains “Finalizing” until the server returns COMPLETED with a job ID.
- **P7-C:** Reload/resume requires the same fingerprint; pause stops scheduling new parts; abort only targets OPEN sessions.
- **P7-D:** Job view polls every three seconds while visible/nonterminal, slows while hidden and stops on terminal state/unmount. READY is based on state, not 100% progress.
- **P7-E:** Manual Retry submits once, preserves its idempotency key across response-loss retry and navigates to the new job.
- **P7-F:** Review shows active versus candidate versions, validates required media/artwork and uses the P5 preview transport without writing viewing progress.
- **P7-G:** Publish/Activate/Unpublish/Archive require explicit confirmation and reload authoritative state; Restore produces UNPUBLISHED. No delete action exists.
- **P7-H:** Audit renders escaped field changes with filters and stable pagination; secret/token/key fields are absent.
- **P7-I:** Upload, processing, READY, FAILED, PUBLISHED and ARCHIVED states are keyboard-readable and not communicated by color alone.

## Implementation sequence

- [ ] Extend `admin-api.test.ts` and `AdminApi` for media operations. Run only that test file until header/idempotency and error mapping pass.
- [ ] Add `upload-manager.test.ts` for fingerprint byte layout, four-request concurrency, authoritative resume, credentials omission, last-part sizing and completion response loss.

Representative upload assertion:

```typescript
expect(uploadedPartNumbers).toEqual([2, 3]);
expect(maxParallelRequests).toBeLessThanOrEqual(4);
expect(storageRequest.credentials).toBe('omit');
expect(storageRequest.headers.get('Authorization')).toBeNull();
```

- [ ] Implement upload manager/page, running only `upload-manager.test.ts` after each transport change.
- [ ] Add job polling/retry cases to `admin-media.test.tsx`; implement the jobs page and processing step.
- [ ] Add preview/publication/lifecycle cases and reuse `createMediaTokenSession`/`createPrivateHls` from P5. Do not create a second token-refresh implementation.
- [ ] Add audit filter/XSS-safe rendering cases and implement the audit page.
- [ ] Run `npm.cmd --prefix frontend test -- src/api/admin-api.test.ts src/admin/uploads/upload-manager.test.ts src/admin/admin-media.test.tsx` after the final shared Admin edit.
- [ ] Run `npm.cmd --prefix frontend run build` once because Admin routes/types changed.
- [ ] Perform one real browser journey against local services: upload small video/artwork -> READY -> preview -> publish -> open audit. Verify one resumed upload and no watch-progress request during preview.
- [ ] Review the UI for hidden secrets and absence of Delete. Update ledger and write `handoffs/P7.md`. Stop; do not start P8.

## Packet completion evidence

Require the three focused test files, one build and the consolidated local browser journey. Do not run all frontend tests or repeat P6 responsive inspection unless P7 changed shared layout CSS.

Commit explicit P7 paths with message `feat(admin-media): complete P7 admin media UI` when committing is authorized.
