import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

/**
 * The complete admin media MVP acceptance journey, in the P8 acceptance order.
 *
 * `scripts/run_media_acceptance.py` has already built the workspace images, started the disposable
 * Compose project (internal application network, loopback-only web and storage ports), created the
 * private buckets, generated the synthetic fixtures, registered the synthetic accounts, granted
 * ADMIN with the real operator command inside the backend container and started the real media
 * gateway bundle. This spec drives the actual React build through Nginx and the actual APIs:
 * nothing here replaces storage, the worker, the gateway or the backend.
 *
 * API calls are used only for setup and assertions that are not the behavior under test; all
 * uploads, previews, publication, lifecycle and audit behavior runs through the Admin UI.
 */

/** Node's process is a Playwright runtime fact; the spec needs no @types/node dependency. */
declare const process: { env: Record<string, string | undefined> };

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be provided by scripts/run_media_acceptance.py`);
  return value;
}

const BASE = required('MEDIA_ACCEPTANCE_BASE_URL');
const GATEWAY = required('MEDIA_ACCEPTANCE_GATEWAY_URL');
const FIXTURES = required('MEDIA_ACCEPTANCE_FIXTURES');
const ADMIN = {
  username: required('MEDIA_ACCEPTANCE_ADMIN_USERNAME'),
  password: required('MEDIA_ACCEPTANCE_ADMIN_PASSWORD'),
};
const VIEWER = {
  username: required('MEDIA_ACCEPTANCE_VIEWER_USERNAME'),
  password: required('MEDIA_ACCEPTANCE_VIEWER_PASSWORD'),
};

interface AdminMovie {
  id: string;
  lifecycle: 'DRAFT' | 'PUBLISHED' | 'UNPUBLISHED' | 'ARCHIVED';
  revision: number;
  activeMediaVersionId: string | null;
  posterAssetId: string | null;
  backdropAssetId: string | null;
}

interface MediaVersion { id: string; state: string }
interface MediaAsset { id: string; kind: 'POSTER' | 'BACKDROP'; state: string }
interface JobView { id: string; state: string }

interface PlaybackGrant {
  movieId: string;
  manifestUrl: string;
  resumePositionSeconds: number;
  sessionId: string;
  mediaVersionId: string;
  mediaToken: string;
  mediaTokenExpiresAt: string;
}

function fixture(name: string): string {
  return `${FIXTURES}/${name}`;
}

function auth(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

async function login(request: APIRequestContext, user: { username: string; password: string }): Promise<string> {
  const response = await request.post(`${BASE}/api/v1/auth/login`, { data: user });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { accessToken: string }).accessToken;
}

async function adminMovie(request: APIRequestContext, token: string, movieId: string): Promise<AdminMovie> {
  const response = await request.get(`${BASE}/api/v1/admin/movies/${movieId}`, { headers: auth(token) });
  expect(response.status()).toBe(200);
  return (await response.json()) as AdminMovie;
}

async function adminVersions(request: APIRequestContext, token: string, movieId: string): Promise<MediaVersion[]> {
  const response = await request.get(`${BASE}/api/v1/admin/movies/${movieId}/versions?size=100`, { headers: auth(token) });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { items: MediaVersion[] }).items;
}

async function adminAssets(request: APIRequestContext, token: string, movieId: string): Promise<MediaAsset[]> {
  const response = await request.get(`${BASE}/api/v1/admin/movies/${movieId}/assets?size=100`, { headers: auth(token) });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { items: MediaAsset[] }).items;
}

async function adminJob(request: APIRequestContext, token: string, jobId: string): Promise<JobView> {
  const response = await request.get(`${BASE}/api/v1/admin/jobs/${jobId}`, { headers: auth(token) });
  expect(response.status()).toBe(200);
  return (await response.json()) as JobView;
}

async function viewerGrant(
  request: APIRequestContext,
  token: string,
  movieId: string,
): Promise<PlaybackGrant> {
  const response = await request.get(`${BASE}/api/v1/movies/${movieId}/playback`, { headers: auth(token) });
  expect(response.status()).toBe(200);
  return (await response.json()) as PlaybackGrant;
}

function jwtClaims(token: string): Record<string, unknown> {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
  const padded = payload + '='.repeat((4 - (payload.length % 4)) % 4);
  return JSON.parse(atob(padded)) as Record<string, unknown>;
}

async function signInThroughDialog(page: Page, user: { username: string; password: string }): Promise<void> {
  await page.locator('#auth-username').fill(user.username);
  await page.locator('#auth-password').fill(user.password);
  await page.locator('.auth-dialog button[type="submit"]').click();
}

async function waitForPlayerDecode(page: Page, label: string, advance: boolean): Promise<void> {
  const player = page.getByLabel(label);
  await expect(player).toBeVisible({ timeout: 60_000 });
  await expect.poll(
    async () => player.evaluate((element) => {
      const video = element as HTMLVideoElement;
      return video.readyState >= 3 && video.videoWidth > 0;
    }),
    { timeout: 120_000, intervals: [500, 1000, 2000] },
  ).toBe(true);
  if (advance) {
    // The player reports whole-second positions, so crossing 0.2s is not enough: pausing below
    // one second would legitimately write resumePositionSeconds 0.
    await expect.poll(
      async () => player.evaluate((element) => (element as HTMLVideoElement).currentTime),
      { timeout: 120_000, intervals: [500, 1000, 2000] },
    ).toBeGreaterThanOrEqual(1);
  }
}

async function uploadThroughUi(
  page: Page,
  kind: 'VIDEO' | 'POSTER' | 'BACKDROP',
  fileName: string,
  options: { resume?: boolean } = {},
): Promise<string> {
  await page.getByLabel('Media kind').selectOption(kind);
  await page.getByLabel('Media file').setInputFiles(fixture(fileName));
  let release: (() => void) | null = null;
  if (options.resume) {
    const gate = new Promise<void>((resolve) => { release = resolve; });
    await page.route('**/media-source/**', async (route) => {
      if (route.request().method() === 'PUT') {
        await gate;
        await route.continue();
      } else {
        await route.continue();
      }
    });
  }
  await page.getByRole('button', { name: 'Start upload' }).click();
  if (options.resume) {
    await expect(page.locator('.admin-upload__status')).toContainText('Uploading parts…', { timeout: 60_000 });
    await page.getByRole('button', { name: 'Pause' }).click();
    release!();
    await expect(page.locator('.admin-upload__status')).toContainText('Paused.', { timeout: 120_000 });
    const journal = JSON.parse(
      (await page.evaluate(() => window.localStorage.getItem('lvfast.admin.upload'))) ?? 'null',
    ) as { movieId: string; fileName: string; uploadId: string } | null;
    expect(journal?.movieId).toBeTruthy();
    expect(journal?.fileName).toBe(fileName);
    // The resume guard only accepts the same file again, so reselect it before resuming.
    await page.getByLabel('Media file').setInputFiles(fixture(fileName));
    await page.getByRole('button', { name: `Resume ${fileName}` }).click();
  }
  await expect(page.locator('.admin-upload__status')).toContainText('Upload completed.', { timeout: 300_000 });
  await page.unroute('**/media-source/**').catch(() => undefined);
  const href = await page.getByRole('link', { name: 'Track the processing job' }).getAttribute('href');
  expect(href).toContain('/admin/jobs/');
  return href!.split('/').pop()!;
}

async function expectNoDeleteControl(page: Page): Promise<void> {
  await expect(page.getByRole('button', { name: /delete|remove|destroy/i })).toHaveCount(0);
  await expect(page.getByRole('link', { name: /delete|remove|destroy/i })).toHaveCount(0);
}

test('complete admin and viewer journey', async ({ page, request, browser }) => {
  const adminToken = await login(request, ADMIN);
  const viewerToken = await login(request, VIEWER);
  const suffix = Math.random().toString(36).slice(2, 8);
  const title = `P8 Journey ${suffix}`;
  const slug = `p8-journey-${suffix}`;
  let movieId = '';
  let videoJobId = '';
  let initialVersionId = '';
  let replacementVersionId = '';
  let posterAssetId = '';
  let backdropAssetId = '';
  let initialGrant: PlaybackGrant | null = null;

  await test.step('step 1: register an account and grant ADMIN with the operator command', async () => {
    // The operator grant and both registrations ran in scripts/run_media_acceptance.py; this step
    // proves the granted account reaches the Admin workspace while a plain USER does not.
    await page.goto('/admin/movies');
    await expect(page.getByRole('heading', { name: 'Sign in required' })).toBeVisible();
    await page.getByRole('button', { name: 'Sign in' }).click();
    await signInThroughDialog(page, ADMIN);
    await expect(page.getByRole('heading', { name: 'Films' })).toBeVisible({ timeout: 30_000 });
    const denied = await request.get(`${BASE}/api/v1/admin/movies`, { headers: auth(viewerToken) });
    expect(denied.status()).toBe(403);
  });

  await test.step('step 2: create a draft and verify USER denial plus stale ETag rejection', async () => {
    await page.getByRole('link', { name: 'New film' }).click();
    await page.locator('#field-title').fill(title);
    await page.locator('#field-slug').fill(slug);
    await page.locator('#field-synopsis').fill('An isolated acceptance journey for the admin media MVP.');
    await page.locator('.admin-genres__list input[type="checkbox"]').first().check();
    await page.getByRole('button', { name: 'Save and continue' }).click();
    await page.waitForURL(/\/admin\/movies\/[0-9a-f-]{36}$/);
    movieId = page.url().split('/').pop()!;

    const current = await request.get(`${BASE}/api/v1/admin/movies/${movieId}`, { headers: auth(adminToken) });
    expect(current.status()).toBe(200);
    const etag = current.headers()['etag'];

    const body = {
      title,
      slug,
      synopsis: 'An isolated acceptance journey for the admin media MVP.',
      releaseYear: 2026,
      maturityRating: 'PG',
      genreIds: [] as number[],
      featured: false,
    };
    const denied = await request.post(`${BASE}/api/v1/admin/movies`, {
      headers: { ...auth(viewerToken), 'Idempotency-Key': `p8-denied-${suffix}` },
      data: { ...body, slug: `${slug}-denied` },
    });
    expect(denied.status()).toBe(403);

    const stale = await request.put(`${BASE}/api/v1/admin/movies/${movieId}`, {
      headers: { ...auth(adminToken), 'If-Match': '"999999"' },
      data: body,
    });
    expect(stale.status()).toBe(412);

    const missing = await request.put(`${BASE}/api/v1/admin/movies/${movieId}`, {
      headers: auth(adminToken),
      data: body,
    });
    expect(missing.status()).toBe(428);
    expect(etag).toBeTruthy();
  });

  await test.step('step 3: upload poster, backdrop and video, interrupting and resuming the video', async () => {
    await page.getByRole('link', { name: 'Uploads' }).click();
    await expect(page.getByRole('heading', { name: 'Upload media' })).toBeVisible();
    await uploadThroughUi(page, 'POSTER', 'poster.jpg');
    await uploadThroughUi(page, 'BACKDROP', 'backdrop.jpg');
    videoJobId = await uploadThroughUi(page, 'VIDEO', 'original.mp4', { resume: true });
    expect(videoJobId).toBeTruthy();
  });

  await test.step('step 4: observe RabbitMQ and worker processing and READY output', async () => {
    await page.goto(`/admin/jobs/${videoJobId}`);
    await expect(page.locator('.admin-job-detail .admin-badge--job-succeeded')).toBeVisible({ timeout: 300_000 });
    await expect.poll(
      async () => (await adminVersions(request, adminToken, movieId)).some((version) => version.state === 'READY'),
      { timeout: 300_000, intervals: [1000, 3000] },
    ).toBe(true);
    await expect.poll(
      async () => (await adminAssets(request, adminToken, movieId)).filter((asset) => asset.state === 'READY').length,
      { timeout: 300_000, intervals: [1000, 3000] },
    ).toBeGreaterThanOrEqual(2);

    const versions = await adminVersions(request, adminToken, movieId);
    initialVersionId = versions.find((version) => version.state === 'READY')!.id;
    const assets = await adminAssets(request, adminToken, movieId);
    posterAssetId = assets.find((asset) => asset.kind === 'POSTER' && asset.state === 'READY')!.id;
    backdropAssetId = assets.find((asset) => asset.kind === 'BACKDROP' && asset.state === 'READY')!.id;
  });

  await test.step('step 5: preview without writing personal progress, attach artwork and publish', async () => {
    const personalProgressRequests: string[] = [];
    page.on('request', (intercepted) => {
      if (intercepted.url().includes('/api/v1/me/progress')) personalProgressRequests.push(intercepted.url());
    });

    await page.goto(`/admin/movies/${movieId}/review`);
    await expect(page.getByRole('heading', { name: 'Media versions' })).toBeVisible();
    await page.getByRole('button', { name: 'Preview selected' }).click();
    await waitForPlayerDecode(page, 'Preview player', false);
    expect(personalProgressRequests).toEqual([]);

    for (const [kind, assetId] of [['Poster', posterAssetId], ['Backdrop', backdropAssetId]] as const) {
      const row = page.locator('.admin-artwork-row', { hasText: kind });
      await row.getByRole('button', { name: 'Attach' }).click();
      await expect(page.locator('.admin-form__notice')).toContainText(`${kind.toLowerCase()} artwork was attached`);
    }

    await page.getByRole('button', { name: 'Publish', exact: true }).click();
    await page.getByRole('button', { name: 'Confirm publish' }).click();
    await expect(page.locator('.admin-page__header .admin-badge')).toHaveText('PUBLISHED', { timeout: 60_000 });

    const published = await adminMovie(request, adminToken, movieId);
    expect(published.lifecycle).toBe('PUBLISHED');
    expect(published.activeMediaVersionId).toBe(initialVersionId);
    expect(published.posterAssetId).toBe(posterAssetId);

    const promoted = await request.get(`${GATEWAY}/public-artwork/${posterAssetId}/image.jpg`);
    expect(promoted.status()).toBe(200);
    expect(promoted.headers()['content-type']).toContain('image/jpeg');
  });

  await test.step('step 6: view protected playback through the gateway and prove 401 and 206', async () => {
    const viewerContext = await browser.newContext();
    const viewerPage = await viewerContext.newPage();
    await viewerPage.goto(`${BASE}/`);
    const loginStatus = await viewerPage.evaluate(async (credentials) => {
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(credentials),
      });
      return response.status;
    }, VIEWER);
    expect(loginStatus).toBe(200);
    await viewerPage.goto(`${BASE}/watch/${movieId}`);
    try {
      await waitForPlayerDecode(viewerPage, 'Video player', true);

      initialGrant = await viewerGrant(request, viewerToken, movieId);
      expect(initialGrant.mediaVersionId).toBe(initialVersionId);
      expect(initialGrant.manifestUrl).toContain(`/hls/${movieId}/${initialVersionId}/`);

      const unauthorized = await request.get(`${GATEWAY}${initialGrant.manifestUrl}`);
      expect(unauthorized.status()).toBe(401);

      const manifest = await request.get(`${GATEWAY}${initialGrant.manifestUrl}`, {
        headers: auth(initialGrant.mediaToken),
      });
      expect(manifest.status()).toBe(200);
      const segment = (await manifest.text()).split('\n')
        .map((line) => line.trim())
        .find((line) => line.length > 0 && !line.startsWith('#') && line.endsWith('.ts'));
      expect(segment).toBeTruthy();
      const prefix = initialGrant.manifestUrl.slice(0, initialGrant.manifestUrl.lastIndexOf('/') + 1);
      const ranged = await request.get(`${GATEWAY}${prefix}${segment}`, {
        headers: { ...auth(initialGrant.mediaToken), Range: 'bytes=0-1023' },
      });
      expect(ranged.status()).toBe(206);
      expect(ranged.headers()['content-range']).toMatch(/^bytes 0-1023\//);

      // Pausing the real player persists progress for the pinned version, which the next steps use
      // to prove that an admin preview never disturbs a viewer's place.
      await viewerPage.getByLabel('Video player').evaluate((element) => (element as HTMLVideoElement).pause());
      await expect.poll(
        async () => (await viewerGrant(request, viewerToken, movieId)).resumePositionSeconds,
        { timeout: 30_000, intervals: [500, 1000] },
      ).toBeGreaterThan(0);
    } finally {
      await viewerContext.close();
    }
  });

  await test.step('step 7: activate a replacement while the old session stays pinned', async () => {
    const oldGrant = initialGrant;
    expect(oldGrant).toBeTruthy();
    await page.goto(`/admin/movies/${movieId}/uploads`);
    const replacementJob = await uploadThroughUi(page, 'VIDEO', 'replacement.mp4');
    await expect.poll(
      async () => (await adminJob(request, adminToken, replacementJob)).state,
      { timeout: 300_000, intervals: [1000, 3000] },
    ).toBe('SUCCEEDED');
    await expect.poll(
      async () => (await adminVersions(request, adminToken, movieId))
        .filter((version) => version.state === 'READY').length,
      { timeout: 300_000, intervals: [1000, 3000] },
    ).toBeGreaterThanOrEqual(2);
    const versions = await adminVersions(request, adminToken, movieId);
    replacementVersionId = versions
      .find((version) => version.state === 'READY' && version.id !== initialVersionId)!.id;

    await page.goto(`/admin/movies/${movieId}/review`);
    const beforePreview = await viewerGrant(request, viewerToken, movieId);
    const candidate = page.locator('.admin-version', { hasText: 'Candidate' }).getByRole('radio');
    await candidate.check();
    await page.getByRole('button', { name: 'Preview selected' }).click();
    await waitForPlayerDecode(page, 'Preview player', false);
    const afterPreview = await viewerGrant(request, viewerToken, movieId);
    expect(afterPreview.mediaVersionId).toBe(initialVersionId);
    expect(afterPreview.resumePositionSeconds).toBe(beforePreview.resumePositionSeconds);

    await candidate.check();
    await page.getByRole('button', { name: 'Activate replacement' }).click();
    await page.getByRole('button', { name: 'Confirm activate replacement' }).click();
    await expect.poll(
      async () => (await adminMovie(request, adminToken, movieId)).activeMediaVersionId,
      { timeout: 60_000, intervals: [500, 1000] },
    ).toBe(replacementVersionId);

    const replacementGrant = await viewerGrant(request, viewerToken, movieId);
    expect(replacementGrant.mediaVersionId).toBe(replacementVersionId);
    expect(replacementGrant.resumePositionSeconds).toBe(0);

    const refreshed = await request.post(
      `${BASE}/api/v1/me/playback-sessions/${oldGrant!.sessionId}/token`,
      { headers: auth(viewerToken) },
    );
    expect(refreshed.status()).toBe(200);
    const refreshedToken = ((await refreshed.json()) as { mediaToken: string }).mediaToken;
    const claims = jwtClaims(refreshedToken);
    expect(claims.movieId).toBe(movieId);
    expect(claims.versionId).toBe(initialVersionId);
    const pinned = await request.get(`${GATEWAY}${oldGrant!.manifestUrl}`, { headers: auth(refreshedToken) });
    expect(pinned.status()).toBe(200);

    const fresh = await request.get(`${GATEWAY}${replacementGrant.manifestUrl}`, {
      headers: auth(replacementGrant.mediaToken),
    });
    expect(fresh.status()).toBe(200);
  });

  await test.step('step 8: unpublish, archive and restore with new playback denial', async () => {
    await page.goto(`/admin/movies/${movieId}/review`);
    await page.getByRole('button', { name: 'Unpublish' }).click();
    await page.getByRole('button', { name: 'Confirm unpublish' }).click();
    await expect(page.locator('.admin-page__header .admin-badge')).toHaveText('UNPUBLISHED', { timeout: 60_000 });
    const deniedGrant = await request.get(`${BASE}/api/v1/movies/${movieId}/playback`, { headers: auth(viewerToken) });
    expect(deniedGrant.status()).toBe(404);
    const deniedRefresh = await request.post(
      `${BASE}/api/v1/me/playback-sessions/${initialGrant!.sessionId}/token`,
      { headers: auth(viewerToken) },
    );
    expect(deniedRefresh.status()).toBe(404);

    await page.getByRole('button', { name: 'Archive' }).click();
    await page.getByRole('button', { name: 'Confirm archive' }).click();
    await expect(page.locator('.admin-page__header .admin-badge')).toHaveText('ARCHIVED', { timeout: 60_000 });

    await page.getByRole('button', { name: 'Restore' }).click();
    await page.getByRole('button', { name: 'Confirm restore' }).click();
    await expect(page.locator('.admin-page__header .admin-badge')).toHaveText('UNPUBLISHED', { timeout: 60_000 });
    const restored = await adminMovie(request, adminToken, movieId);
    expect(restored.lifecycle).toBe('UNPUBLISHED');
    expect(restored.activeMediaVersionId).toBe(replacementVersionId);
  });

  await test.step('step 9: audit view contains the important actions and no Delete action', async () => {
    const audit = await request.get(
      `${BASE}/api/v1/admin/audit?entityId=${movieId}&size=100`,
      { headers: auth(adminToken) },
    );
    expect(audit.status()).toBe(200);
    const actions = ((await audit.json()) as { items: Array<{ action: string }> }).items.map((item) => item.action);
    for (const action of [
      'MOVIE_CREATED', 'MOVIE_ARTWORK_ATTACHED', 'MOVIE_PUBLISHED', 'MOVIE_VERSION_ACTIVATED',
      'MOVIE_UNPUBLISHED', 'MOVIE_ARCHIVED', 'MOVIE_RESTORED', 'MOVIE_PREVIEWED',
    ]) {
      expect(actions).toContain(action);
    }
    const roleGrant = await request.get(`${BASE}/api/v1/admin/audit?action=ROLE_GRANTED&size=100`, {
      headers: auth(adminToken),
    });
    expect(roleGrant.status()).toBe(200);
    expect(((await roleGrant.json()) as { items: unknown[] }).items.length).toBeGreaterThan(0);

    await page.goto('/admin/audit');
    await page.getByLabel('Filter by action').selectOption('MOVIE_PUBLISHED');
    await expect(page.locator('.admin-audit__event .admin-badge').first()).toHaveText('MOVIE_PUBLISHED');
    await expectNoDeleteControl(page);

    for (const route of ['/admin/movies', `/admin/movies/${movieId}`, `/admin/movies/${movieId}/review`, '/admin/jobs']) {
      await page.goto(route);
      await expectNoDeleteControl(page);
    }
  });
});

