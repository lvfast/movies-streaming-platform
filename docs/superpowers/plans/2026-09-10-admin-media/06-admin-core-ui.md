# P6 — Admin Core UI

**Outcome:** A current administrator can enter a lazy-loaded `/admin` workspace, browse managed/legacy films and create or edit drafts with revision-conflict handling.

**Depends on:** P5 DONE and `handoffs/P5.md` reviewed.

## Files

Create:

- `frontend/src/api/admin-api.ts`
- `frontend/src/api/admin-api.test.ts`
- `frontend/src/admin/admin-routes.tsx`
- `frontend/src/admin/admin-layout.tsx`
- `frontend/src/admin/admin-guard.tsx`
- `frontend/src/admin/admin.css`
- `frontend/src/admin/library/film-library-page.tsx`
- `frontend/src/admin/library/film-table.tsx`
- `frontend/src/admin/editor/movie-editor-page.tsx`
- `frontend/src/admin/editor/movie-form.tsx`
- `frontend/src/admin/editor/revision-conflict.tsx`
- `frontend/src/admin/admin-core.test.tsx`
- `docs/superpowers/specs/2026-09-10-admin-visual-design.md`

Modify:

- `frontend/src/App.tsx`
- `frontend/src/session/session-context.tsx`
- `frontend/src/api/api-context.tsx`
- `frontend/src/api/streaming-api.ts`
- `frontend/src/test/fixtures.ts`
- generated files under `frontend/src/api/generated/` only through `npm.cmd --prefix frontend run generate:api`

## Interfaces consumed and produced

Consume generated ADMIN operation IDs and `AdminMovie`/`MovieInput` from [contracts.md](contracts.md).

```typescript
type Versioned<T> = { data: T; revision: number };

interface AdminApi {
  listMovies(query: AdminMovieQuery, signal?: AbortSignal): Promise<AdminMoviePage>;
  getMovie(movieId: string, signal?: AbortSignal): Promise<Versioned<AdminMovie>>;
  createMovie(input: MovieInput, requestKey: string): Promise<Versioned<AdminMovie>>;
  updateMovie(movieId: string, input: MovieInput,
              revision: number): Promise<Versioned<AdminMovie>>;
  listGenres(signal?: AbortSignal): Promise<Genre[]>;
}
```

The wrapper uses the existing `ProblemError` convention. A login-token refresh may retry once while preserving the same request body/idempotency key. A 403 does not trigger a refresh loop.

## Visual baseline

Use a compact operations workspace that reuses the application's existing color and typography direction. Define admin-scoped tokens for background, surfaces, text, muted text, accent, danger, border, focus, spacing and control radius in the visual spec during this packet. No separate mockup/approval session is required unless the user requests one.

Desktop uses a persistent side navigation and full-width content. At 390px navigation becomes a compact top/drawer pattern and tables expose essential fields without horizontal clipping. Color is never the only state indicator.

## Acceptance criteria

- **P6-A:** `/admin` is lazy-loaded and redirects to `/admin/movies`; public routes do not download/render the Admin bundle.
- **P6-B:** Guest and USER see a clear access result; ADMIN enters. Refreshed role revocation removes access, while backend remains authoritative.
- **P6-C:** Library supports search, lifecycle/media-state filters, stable pagination/sort and URL persistence with loading/error/empty states.
- **P6-D:** Create/edit form uses server limits, genre lookup and explicit Save draft/Save and continue actions. Runtime/media URLs are not editable.
- **P6-E:** Slug is editable until first publish. Archived movies are read-only.
- **P6-F:** A 412 keeps unsaved input and offers Reload latest with explicit discard confirmation; it never retries as overwrite.
- **P6-G:** Layout and keyboard focus remain usable at 1280px, 1024px and 390px.

## Implementation sequence

- [ ] Regenerate the client once from the P5 OpenAPI and inspect exact generated names before writing `AdminApi`.
- [ ] Add `admin-api.test.ts` for ETag extraction, `If-Match`, preserved idempotency key, one 401 refresh and 412 mapping. Run only this file while implementing the wrapper.
- [ ] Write the compact visual decisions to `2026-09-10-admin-visual-design.md`; validate that every token has a concrete value and no consumer-page CSS changes are planned.
- [ ] Add `admin-core.test.tsx` route/guard cases, then implement lazy routes, layout and current-role guard.
- [ ] Add library cases to the same test file and implement URL-backed filters, debounced/cancelled requests and explicit states.
- [ ] Add form/create/update/conflict cases and implement the editor. Keep form state after 412.

Representative conflict assertion:

```typescript
expect(await screen.findByRole('alert'))
  .toHaveTextContent('This film was updated by another administrator');
expect(screen.getByLabelText('Title')).toHaveValue('My unsaved title');
```

- [ ] Run `npm.cmd --prefix frontend test -- src/api/admin-api.test.ts src/admin/admin-core.test.tsx` after the final shared UI/API edit.
- [ ] Run `npm.cmd --prefix frontend run build` once because routes, generated types and TypeScript compilation changed.
- [ ] Inspect the Admin library/editor once at 1280px, 1024px and 390px, including keyboard focus and one error/empty state. Record observations rather than rerunning browser inspection per component.
- [ ] Update ledger and write `handoffs/P6.md`. Stop; do not start P7.

## Packet completion evidence

Require the two focused test files, one final frontend build and one consolidated responsive/keyboard inspection. Do not run all frontend tests.

Commit explicit P6 paths with message `feat(admin-media): complete P6 admin core UI` when committing is authorized.
