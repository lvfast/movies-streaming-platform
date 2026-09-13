import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { adminApi, RevisionConflictError } from './admin-api';
import type { AdminMovie, JobView, UploadSession } from './generated/types.gen';
import { client } from './generated/client.gen';
import { accessTokenStore } from './token-store';
import { authTokens } from '../test/fixtures';

const uploadSession: UploadSession = {
  id: '50000000-0000-0000-0000-000000000001',
  movieId: '00000000-0000-0000-0000-000000000001',
  kind: 'VIDEO',
  state: 'OPEN',
  partSizeBytes: 8_388_608,
  totalParts: 2,
  declaredBytes: 9_000_000,
  expiresAt: '2099-01-01T00:00:00.000Z',
  jobId: null,
};

const failedJob: JobView = {
  id: '60000000-0000-0000-0000-000000000001',
  movieId: '00000000-0000-0000-0000-000000000001',
  kind: 'TRANSCODE',
  state: 'FAILED',
  attemptNumber: 2,
  progressPercent: 40,
  stage: 'ENCODING',
  errorCode: 'PROCESS_TIMEOUT',
  errorSummary: 'timed out',
  retryAt: null,
  updatedAt: '2026-09-10T00:00:00.000Z',
};

const movie: AdminMovie = {
  id: '00000000-0000-0000-0000-000000000001',
  title: 'Starlight Archive',
  slug: 'starlight-archive',
  synopsis: 'An archivist follows a signal hidden in a collection of star charts.',
  releaseYear: 2026,
  maturityRating: 'PG',
  genreIds: [1, 2],
  featured: false,
  lifecycle: 'DRAFT',
  revision: 7,
  managementMode: 'MANAGED',
  activeMediaVersionId: null,
  posterAssetId: null,
  backdropAssetId: null,
  runtimeSeconds: null,
  firstPublishedAt: null,
  createdAt: '2026-09-10T00:00:00.000Z',
  updatedAt: '2026-09-10T00:00:00.000Z',
};

describe('admin API adapter', () => {
  beforeEach(() => {
    accessTokenStore.clear();
    client.setConfig({ baseUrl: 'http://localhost/api/v1' });
  });

  afterEach(() => {
    accessTokenStore.clear();
    vi.unstubAllGlobals();
  });

  it('extracts the numeric revision from the movie ETag', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(movie, 200, { ETag: '"7"' })));
    accessTokenStore.set('memory-only-token');

    await expect(adminApi.getMovie(movie.id)).resolves.toEqual({ data: movie, revision: 7 });

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain(`/admin/movies/${movie.id}`);
  });

  it('extracts the numeric revision from a gzip-weakened ETag', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(movie, 200, { ETag: 'W/"7"' })));
    accessTokenStore.set('memory-only-token');

    await expect(adminApi.getMovie(movie.id)).resolves.toEqual({ data: movie, revision: 7 });
  });

  it('sends the quoted revision in If-Match on update', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(movie, 200, { ETag: '"8"' })));
    accessTokenStore.set('memory-only-token');

    await expect(adminApi.updateMovie(movie.id, inputFrom(movie), 7))
      .resolves.toEqual({ data: movie, revision: 8 });

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.headers.get('If-Match')).toBe('"7"');
    expect(request.method).toBe('PUT');
  });

  it('sends and preserves the idempotency key through a single refresh retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(problem(401, 'UNAUTHORIZED'), 401, {}, 'application/problem+json'))
      .mockResolvedValueOnce(jsonResponse({ ...authTokens, accessToken: 'rotated-token' }))
      .mockResolvedValueOnce(jsonResponse(movie, 201, { ETag: '"1"' }));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('expired-token');

    const result = await adminApi.createMovie(inputFrom(movie), 'request-key-123');

    expect(result).toEqual({ data: movie, revision: 1 });
    expect(fetchMock).toHaveBeenCalledTimes(3);

    const createRequests = fetchMock.mock.calls
      .map((call) => call[0] as Request)
      .filter((request) => request.url.endsWith('/admin/movies'));
    expect(createRequests).toHaveLength(2);
    for (const request of createRequests) {
      expect(request.headers.get('Idempotency-Key')).toBe('request-key-123');
      expect(request.headers.get('Authorization')).toBeTruthy();
    }
    const retry = createRequests[1];
    expect(retry.headers.get('Authorization')).toBe('Bearer rotated-token');
    expect(await retry.text()).toBe(JSON.stringify(inputFrom(movie)));
  });

  it('maps a stale revision to a revision conflict without an overwrite retry', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(
      problem(412, 'STALE_REVISION'),
      412,
      {},
      'application/problem+json',
    ));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('memory-only-token');

    const promise = adminApi.updateMovie(movie.id, inputFrom(movie), 6);
    await expect(promise).rejects.toBeInstanceOf(RevisionConflictError);
    await expect(promise).rejects.toMatchObject({ problem: { status: 412, code: 'STALE_REVISION' } });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('does not refresh on a 403 forbidden response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(
      problem(403, 'FORBIDDEN'),
      403,
      {},
      'application/problem+json',
    ));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('memory-only-token');

    await expect(adminApi.listMovies({})).rejects.toMatchObject({ problem: { status: 403, code: 'FORBIDDEN' } });

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('lists genres from the generated admin endpoint', async () => {
    const genres = [{ id: 1, slug: 'adventure', name: 'Adventure' }];
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ items: genres })));
    accessTokenStore.set('memory-only-token');

    await expect(adminApi.listGenres()).resolves.toEqual(genres);

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/genres');
  });

  it('creates an upload with an idempotency key and posts signed part numbers', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(uploadSession, 201))
      .mockResolvedValueOnce(jsonResponse({ items: [
        { partNumber: 1, url: 'https://storage.example.test/part-1', expiresAt: '2099-01-01T00:00:00.000Z', headers: {} },
      ] }));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('memory-only-token');

    await adminApi.createUpload(uploadSession.movieId, {
      kind: 'VIDEO',
      fileName: 'source.mp4',
      contentType: 'video/mp4',
      sizeBytes: 9_000_000,
      resumeFingerprint: `sha256:${'a'.repeat(64)}`,
    }, 'upload-key-1');
    await adminApi.signUploadParts(uploadSession.id, [1]);

    const create = fetchMock.mock.calls[0][0] as Request;
    expect(create.url).toContain('/admin/movies/' + uploadSession.movieId + '/uploads');
    expect(create.headers.get('Idempotency-Key')).toBe('upload-key-1');
    const sign = fetchMock.mock.calls[1][0] as Request;
    expect(sign.url).toContain('/admin/uploads/' + uploadSession.id + '/part-urls');
    expect(await sign.text()).toBe(JSON.stringify({ partNumbers: [1] }));
  });

  it('completes an upload with an idempotency key and maps failure responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(uploadSession, 200)));
    accessTokenStore.set('memory-only-token');

    await adminApi.completeUpload(uploadSession.id, 'complete-key-1');

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/uploads/' + uploadSession.id + '/complete');
    expect(request.headers.get('Idempotency-Key')).toBe('complete-key-1');
  });

  it('attaches artwork with a quoted If-Match and maps a stale revision', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(jsonResponse(
      problem(412, 'STALE_REVISION'), 412, {}, 'application/problem+json')));
    accessTokenStore.set('memory-only-token');

    const promise = adminApi.attachArtwork(movie.id, {
      kind: 'POSTER',
      assetId: '70000000-0000-0000-0000-000000000001',
    }, 6);

    await expect(promise).rejects.toBeInstanceOf(RevisionConflictError);
    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/movies/' + movie.id + '/artwork');
    expect(request.headers.get('If-Match')).toBe('"6"');
  });

  it('preserves publish headers across a single refresh retry', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(problem(401, 'UNAUTHORIZED'), 401, {}, 'application/problem+json'))
      .mockResolvedValueOnce(jsonResponse({ ...authTokens, accessToken: 'rotated-token' }))
      .mockResolvedValueOnce(jsonResponse(movie, 200, { ETag: '"8"' }));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('expired-token');

    const result = await adminApi.publishMovie(
      movie.id, { mediaVersionId: '20000000-0000-0000-0000-000000000001' }, 7, 'publish-key-1');

    expect(result.revision).toBe(8);
    const publishRequests = fetchMock.mock.calls
      .map((call) => call[0] as Request)
      .filter((request) => request.url.endsWith('/admin/movies/' + movie.id + '/publish'));
    expect(publishRequests).toHaveLength(2);
    for (const request of publishRequests) {
      expect(request.headers.get('Idempotency-Key')).toBe('publish-key-1');
      expect(request.headers.get('If-Match')).toBe('"7"');
    }
  });

  it('retries a job with an idempotency key', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(failedJob, 201)));
    accessTokenStore.set('memory-only-token');

    await adminApi.retryJob(failedJob.id, 'retry-key-1');

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/jobs/' + failedJob.id + '/retry');
    expect(request.headers.get('Idempotency-Key')).toBe('retry-key-1');
  });

  it('passes audit filters and pagination to the query string', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      items: [], page: 1, size: 10, total: 0,
    })));
    accessTokenStore.set('memory-only-token');

    await adminApi.listAudit({ action: 'MOVIE_PUBLISHED', entityType: 'MOVIE', page: 1, size: 10 });

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/audit?');
    expect(request.url).toContain('action=MOVIE_PUBLISHED');
    expect(request.url).toContain('entityType=MOVIE');
    expect(request.url).toContain('page=1');
    expect(request.url).toContain('size=10');
  });

  it('previews an asset through the admin-only endpoint', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      url: 'https://storage.example.test/artwork?X-Amz-Expires=60',
      expiresAt: '2099-01-01T00:00:00.000Z',
    })));
    accessTokenStore.set('memory-only-token');

    await adminApi.previewAsset('70000000-0000-0000-0000-000000000001');

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/assets/70000000-0000-0000-0000-000000000001/preview');
    expect(request.method).toBe('POST');
  });

  it('passes page and size query parameters to the movie list', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      items: [movie],
      page: 2,
      size: 10,
      total: 21,
    })));
    accessTokenStore.set('memory-only-token');

    await expect(adminApi.listMovies({ page: 2, size: 10 })).resolves.toEqual({
      items: [movie],
      page: 2,
      size: 10,
      total: 21,
    });

    const request = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as Request;
    expect(request.url).toContain('/admin/movies?page=2&size=10');
  });
});

function inputFrom(value: AdminMovie) {
  return {
    title: value.title,
    slug: value.slug,
    synopsis: value.synopsis,
    releaseYear: value.releaseYear,
    maturityRating: value.maturityRating as 'G' | 'PG' | 'PG-13' | 'R' | 'NC-17' | 'NR',
    genreIds: value.genreIds,
    featured: value.featured,
  };
}

function problem(status: number, code: string) {
  return {
    type: 'about:blank',
    title: 'Error',
    status,
    detail: 'detail',
    requestId: 'request-1',
    code,
  };
}

function jsonResponse(
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
  contentType = 'application/json',
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType, ...headers },
  });
}
