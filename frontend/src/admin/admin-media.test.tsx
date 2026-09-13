import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminRoutes } from './admin-routes';
import { ApiProvider } from '../api/api-context';
import { SessionProvider } from '../session/session-context';
import { useJobPolling } from './jobs/use-job-polling';
import { StateBadge } from './jobs/jobs-page';
import {
  adminAuthTokens,
  adminMovie,
  createAdminApi,
  createApi,
  job,
  jobPage,
  mediaAsset,
  mediaVersion,
  previewPlayback,
} from '../test/fixtures';
import type { AdminApi, AuditEventPage, JobView, MediaAssetPage, MediaVersionPage } from '../api/admin-api';

vi.mock('../playback/private-hls', () => ({
  attachPrivateHls: vi.fn(() => ({
    hls: {},
    tokenSession: { getToken: async () => 'media-token', invalidate: () => undefined, dispose: () => undefined },
    destroy: () => undefined,
    dispose: () => undefined,
  })),
  isManagedPlayback: () => true,
}));

function renderAdmin(api: AdminApi, route: string, streaming = createApi({
  restoreSession: vi.fn().mockResolvedValue(adminAuthTokens),
})) {
  return render(
    <ApiProvider api={streaming}>
      <SessionProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path="/admin/*" element={<AdminRoutes api={api} />} />
          </Routes>
        </MemoryRouter>
      </SessionProvider>
    </ApiProvider>,
  );
}

function PollHarness({ fetchJob, jobId }: { fetchJob: (id: string) => Promise<JobView>; jobId: string }) {
  const { job: polled } = useJobPolling(fetchJob, jobId, { intervalMs: 3000, hiddenIntervalMs: 15000 });
  return <div>{polled ? polled.state : 'none'}</div>;
}

const runningMovie = {
  ...adminMovie,
  synopsis: 'A ready synopsis.',
  lifecycle: 'DRAFT' as const,
  revision: 5,
  activeMediaVersionId: null,
};

const readyVersions: MediaVersionPage = {
  items: [mediaVersion],
  page: 0,
  size: 100,
  total: 1,
};

const readyArtwork: MediaAssetPage = {
  items: [mediaAsset, { ...mediaAsset, id: '70000000-0000-0000-0000-000000000002', kind: 'BACKDROP' }],
  page: 0,
  size: 100,
  total: 2,
};

describe('job polling', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
  });

  it('polls nonterminal jobs every three seconds and stops on a terminal state', async () => {
    const states: JobView['state'][] = ['RUNNING', 'RUNNING', 'SUCCEEDED'];
    let call = 0;
    const fetchJob = vi.fn(async (_id: string) => {
      const state = states[Math.min(call, states.length - 1)];
      call += 1;
      return { ...job, state };
    });

    render(<PollHarness fetchJob={fetchJob} jobId={job.id} />);
    await act(async () => { await Promise.resolve(); });
    expect(fetchJob).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(fetchJob).toHaveBeenCalledTimes(2);

    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(fetchJob).toHaveBeenCalledTimes(3);

    await act(async () => { vi.advanceTimersByTime(30000); });
    expect(fetchJob).toHaveBeenCalledTimes(3);
  });

  it('slows while the document is hidden', async () => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    const fetchJob = vi.fn(async (_id: string) => ({ ...job, state: 'RUNNING' as const }));

    render(<PollHarness fetchJob={fetchJob} jobId={job.id} />);
    await act(async () => { await Promise.resolve(); });
    expect(fetchJob).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(3000); });
    expect(fetchJob).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(12000); });
    expect(fetchJob).toHaveBeenCalledTimes(2);
  });

  it('stays stopped after a terminal state even when visibility changes', async () => {
    const fetchJob = vi.fn(async (_id: string) => ({ ...job, state: 'SUCCEEDED' as const }));

    render(<PollHarness fetchJob={fetchJob} jobId={job.id} />);
    await act(async () => { await Promise.resolve(); });
    expect(fetchJob).toHaveBeenCalledTimes(1);

    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    await act(async () => { vi.advanceTimersByTime(30000); });

    expect(fetchJob).toHaveBeenCalledTimes(1);
  });

  it('does not start a second polling loop when visibility changes mid-request', async () => {
    let resolveFirst: ((value: JobView) => void) | undefined;
    const fetchJob = vi.fn(() => new Promise<JobView>((resolve) => { resolveFirst = resolve; }));

    render(<PollHarness fetchJob={fetchJob} jobId={job.id} />);
    await act(async () => { await Promise.resolve(); });
    expect(fetchJob).toHaveBeenCalledTimes(1);

    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    await act(async () => { resolveFirst?.({ ...job, state: 'RUNNING' }); });
    await act(async () => { vi.advanceTimersByTime(3000); });

    expect(fetchJob).toHaveBeenCalledTimes(2);
  });
});

describe('job view', () => {
  it('derives completion from state, never from a 100% progress value', async () => {
    const api = createAdminApi({
      getJob: vi.fn().mockResolvedValue({ ...job, state: 'RUNNING', progressPercent: 100 }),
    });
    renderAdmin(api, `/admin/jobs/${job.id}`);

    expect((await screen.findAllByText('Running')).length).toBeGreaterThan(0);
    expect(screen.queryByText('Succeeded')).not.toBeInTheDocument();
    expect(screen.getByText('100%')).toBeVisible();
  });

  it('submits a retry once, reuses the same idempotency key and navigates to the new job', async () => {
    const retried = { ...job, id: '60000000-0000-0000-0000-00000000000f', state: 'QUEUED' as const };
    const getJob = vi.fn().mockImplementation(async (id: string) => (id === retried.id ? retried : { ...job, state: 'FAILED' as const }));
    const retryJob = vi.fn()
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(retried);
    const api = createAdminApi({ getJob, retryJob });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/jobs/${job.id}`);

    await actor.click(await screen.findByRole('button', { name: /retry job/i }));

    await waitFor(() => expect(retryJob).toHaveBeenCalledTimes(2));
    expect(retryJob.mock.calls[0][1]).toBe(retryJob.mock.calls[1][1]);
    await waitFor(() => expect(getJob).toHaveBeenCalledWith(retried.id, expect.anything()));
  });

  it('retries loading the job list after a list error', async () => {
    const listJobs = vi.fn()
      .mockRejectedValueOnce(new Error('list unavailable'))
      .mockResolvedValue(jobPage);
    const api = createAdminApi({ listJobs });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/jobs');

    await actor.click(await screen.findByRole('button', { name: /try again/i }));

    await waitFor(() => expect(listJobs).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('Video')).toBeVisible();
  });

  it('uses a fresh idempotency key for each retry action', async () => {
    const retryJob = vi.fn().mockRejectedValue(new Error('response lost'));
    const api = createAdminApi({
      getJob: vi.fn().mockResolvedValue({ ...job, state: 'FAILED' as const }),
      retryJob,
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/jobs/${job.id}`);

    const button = await screen.findByRole('button', { name: /retry job/i });
    await actor.click(button);
    await waitFor(() => expect(retryJob).toHaveBeenCalledTimes(2));
    await actor.click(button);
    await waitFor(() => expect(retryJob).toHaveBeenCalledTimes(4));

    expect(retryJob.mock.calls[0][1]).toBe(retryJob.mock.calls[1][1]);
    expect(retryJob.mock.calls[2][1]).toBe(retryJob.mock.calls[3][1]);
    expect(retryJob.mock.calls[2][1]).not.toBe(retryJob.mock.calls[0][1]);
  });

  it('renders job states as labelled badges, not color alone', () => {
    render(<StateBadge state="RETRY_WAIT" />);
    expect(screen.getByText('Retry wait')).toBeVisible();
  });
});

describe('movie review', () => {
  it('previews through the admin transport without writing viewing progress', async () => {
    const streaming = createApi({ restoreSession: vi.fn().mockResolvedValue(adminAuthTokens) });
    const api = createAdminApi({
      getMovie: vi.fn().mockResolvedValue({ data: runningMovie, revision: 5 }),
      listVersions: vi.fn().mockResolvedValue(readyVersions),
      listAssets: vi.fn().mockResolvedValue(readyArtwork),
      previewMovie: vi.fn().mockResolvedValue(previewPlayback),
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${runningMovie.id}/review`, streaming);

    await actor.click(await screen.findByRole('button', { name: /preview selected/i }));

    await waitFor(() => expect(api.previewMovie).toHaveBeenCalledWith(runningMovie.id, mediaVersion.id));
    expect(await screen.findByLabelText('Preview player')).toBeVisible();
    expect(streaming.updateProgress).not.toHaveBeenCalled();
  });

  it('keeps the attach action available for artwork that is only the fallback selection', async () => {
    const attachArtwork = vi.fn().mockResolvedValue({
      data: { ...runningMovie, posterAssetId: mediaAsset.id },
      revision: 6,
    });
    const api = createAdminApi({
      getMovie: vi.fn().mockResolvedValue({ data: runningMovie, revision: 5 }),
      listVersions: vi.fn().mockResolvedValue(readyVersions),
      listAssets: vi.fn().mockResolvedValue(readyArtwork),
      attachArtwork,
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${runningMovie.id}/review`);

    const attach = await screen.findAllByRole('button', { name: /attach/i });
    expect(attach).toHaveLength(2);
    expect(attach[0]).toBeEnabled();
    expect(attach[1]).toBeEnabled();

    await actor.click(attach[0]);
    await waitFor(() => expect(attachArtwork).toHaveBeenCalledWith(
      runningMovie.id,
      { kind: 'POSTER', assetId: mediaAsset.id },
      5,
    ));
  });

  it('requires explicit confirmation before publishing and reloads authoritative state', async () => {
    const getMovie = vi.fn()
      .mockResolvedValueOnce({ data: runningMovie, revision: 5 })
      .mockResolvedValue({ data: { ...runningMovie, lifecycle: 'PUBLISHED' }, revision: 6 });
    const publishMovie = vi.fn().mockResolvedValue({
      data: { ...runningMovie, lifecycle: 'PUBLISHED' }, revision: 6,
    });
    const api = createAdminApi({
      getMovie,
      listVersions: vi.fn().mockResolvedValue(readyVersions),
      listAssets: vi.fn().mockResolvedValue(readyArtwork),
      publishMovie,
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${runningMovie.id}/review`);

    await actor.click(await screen.findByRole('button', { name: /^publish$/i }));
    expect(publishMovie).not.toHaveBeenCalled();

    await actor.click(screen.getByRole('button', { name: /confirm publish/i }));

    await waitFor(() => expect(publishMovie).toHaveBeenCalledWith(
      runningMovie.id,
      expect.objectContaining({
        mediaVersionId: mediaVersion.id,
        posterAssetId: mediaAsset.id,
        backdropAssetId: '70000000-0000-0000-0000-000000000002',
      }),
      5,
      expect.any(String),
    ));
    await waitFor(() => expect(getMovie).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('status')).toHaveTextContent('updated');
  });

  it('offers restore instead of delete for an archived film', async () => {
    const archived = { ...adminMovie, lifecycle: 'ARCHIVED' as const };
    const restoreMovie = vi.fn().mockResolvedValue({ data: { ...archived, lifecycle: 'UNPUBLISHED' }, revision: 11 });
    const api = createAdminApi({
      getMovie: vi.fn().mockResolvedValue({ data: archived, revision: 10 }),
      listVersions: vi.fn().mockResolvedValue(readyVersions),
      listAssets: vi.fn().mockResolvedValue(readyArtwork),
      restoreMovie,
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${archived.id}/review`);

    expect(await screen.findByRole('button', { name: /restore/i })).toBeVisible();
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();

    await actor.click(screen.getByRole('button', { name: /restore/i }));
    await actor.click(screen.getByRole('button', { name: /confirm restore/i }));

    await waitFor(() => expect(restoreMovie).toHaveBeenCalledWith(archived.id, 10));
  });

  it('activates only a selected replacement candidate, never the active version', async () => {
    const activeVersion = { ...mediaVersion, id: '20000000-0000-0000-0000-000000000001' };
    const candidateVersion = { ...mediaVersion, id: '30000000-0000-0000-0000-000000000001' };
    const published = {
      ...adminMovie,
      lifecycle: 'PUBLISHED' as const,
      revision: 6,
      activeMediaVersionId: activeVersion.id,
    };
    const activateVersion = vi.fn().mockResolvedValue({ data: published, revision: 7 });
    const api = createAdminApi({
      getMovie: vi.fn().mockResolvedValue({ data: published, revision: 6 }),
      listVersions: vi.fn().mockResolvedValue({
        items: [candidateVersion, activeVersion], page: 0, size: 100, total: 2,
      }),
      listAssets: vi.fn().mockResolvedValue(readyArtwork),
      activateVersion,
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${published.id}/review`);

    const activate = await screen.findByRole('button', { name: /activate replacement/i });
    expect(activate).toBeDisabled();

    await actor.click(screen.getByRole('radio', { name: /30000000/ }));
    expect(activate).toBeEnabled();

    await actor.click(activate);
    await actor.click(screen.getByRole('button', { name: /confirm activate replacement/i }));

    await waitFor(() => expect(activateVersion).toHaveBeenCalledWith(
      published.id,
      { mediaVersionId: candidateVersion.id },
      6,
      expect.any(String),
    ));
  });
});

describe('audit history', () => {
  const xssPage: AuditEventPage = {
    items: [{
      id: 9,
      actorId: null,
      actorType: 'USER',
      action: 'MOVIE_UPDATED',
      entityType: 'MOVIE',
      entityId: adminMovie.id,
      requestId: 'request-9',
      before: { title: 'Old' },
      after: { title: '<b>Not bold</b><img src="x" onerror="window.__xss=1">' },
      createdAt: '2026-09-10T00:00:00.000Z',
    }],
    page: 0,
    size: 20,
    total: 21,
  };

  it('renders escaped field changes and never injects stored markup', async () => {
    const api = createAdminApi({ listAudit: vi.fn().mockResolvedValue(xssPage) });
    renderAdmin(api, '/admin/audit');

    await waitFor(() => expect(document.querySelector('.admin-audit__after')).not.toBeNull());
    expect(document.querySelector('.admin-audit__after')?.textContent)
      .toBe('<b>Not bold</b><img src="x" onerror="window.__xss=1">');
    expect(document.querySelector('.admin-audit img')).toBeNull();
    expect(document.querySelector('.admin-audit b')).toBeNull();
  });

  it('passes filters to the audit query and paginates stably', async () => {
    const listAudit = vi.fn().mockResolvedValue(xssPage);
    const api = createAdminApi({ listAudit });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/audit');

    await screen.findByText(/MOVIE_UPDATED/);
    await actor.selectOptions(screen.getByLabelText(/filter by action/i), 'MOVIE_PUBLISHED');
    await waitFor(() => expect(listAudit).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: 'MOVIE_PUBLISHED', page: 0 }),
      expect.anything(),
    ));

    await actor.click(screen.getByRole('button', { name: /next/i }));
    await waitFor(() => expect(listAudit).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1 }),
      expect.anything(),
    ));
  });

  it('offers filter values that match what the backend records', async () => {
    const api = createAdminApi({
      listAudit: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, total: 0 }),
    });
    renderAdmin(api, '/admin/audit');

    expect(await screen.findByRole('option', { name: 'JOB_RETRIED' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'JOB_RETRY_REQUESTED' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'MEDIA_JOB' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'APP_USER' })).toBeInTheDocument();
  });
});

describe('upload page', () => {
  it('renders kind and file controls and creates an upload with a fingerprint', async () => {
    const createUpload = vi.fn().mockRejectedValue(new Error('stop before network'));
    const api = createAdminApi({ createUpload });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${adminMovie.id}/uploads`);

    expect(await screen.findByLabelText('Media kind')).toHaveValue('VIDEO');
    const file = new File(['hello'], 'clip.mp4', { type: 'video/mp4' });
    await actor.upload(screen.getByLabelText('Media file'), file);
    await actor.click(screen.getByRole('button', { name: /start upload/i }));

    await waitFor(() => expect(createUpload).toHaveBeenCalledWith(
      adminMovie.id,
      expect.objectContaining({
        kind: 'VIDEO',
        fileName: 'clip.mp4',
        resumeFingerprint: expect.stringMatching(/^sha256:[0-9a-f]{64}$/),
      }),
      expect.any(String),
    ));
  });

  it('only resumes a journal entry that belongs to the open movie', async () => {
    const entry = {
      uploadId: 'upload-1',
      movieId: 'some-other-movie',
      fingerprint: `sha256:${'a'.repeat(64)}`,
      fileName: 'other.mp4',
      sizeBytes: 10,
    };
    const file = new File(['hello'], 'clip.mp4', { type: 'video/mp4' });
    window.localStorage.setItem('lvfast.admin.upload', JSON.stringify(entry));
    try {
      const api = createAdminApi();
      const first = renderAdmin(api, `/admin/movies/${adminMovie.id}/uploads`);
      await userEvent.setup().upload(await screen.findByLabelText('Media file'), file);
      expect(screen.queryByRole('button', { name: /resume other\.mp4/i })).not.toBeInTheDocument();
      first.unmount();

      window.localStorage.setItem(
        'lvfast.admin.upload', JSON.stringify({ ...entry, movieId: adminMovie.id }));
      renderAdmin(api, `/admin/movies/${adminMovie.id}/uploads`);
      await userEvent.setup().upload(await screen.findByLabelText('Media file'), file);
      expect(await screen.findByRole('button', { name: /resume other\.mp4/i })).toBeVisible();
    } finally {
      window.localStorage.removeItem('lvfast.admin.upload');
    }
  });
});
