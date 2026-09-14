import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AdminRoutes } from './admin-routes';
import { ApiProvider } from '../api/api-context';
import { SessionProvider } from '../session/session-context';
import { RevisionConflictError } from '../api/admin-api';
import { notifyAuthUser } from '../api/streaming-api';
import { adminAuthTokens, adminMovie, authTokens, createAdminApi, createApi, user } from '../test/fixtures';

function renderAdmin(adminApi = createAdminApi(), route = '/admin', streaming = createApi()) {
  return render(
    <ApiProvider api={streaming}>
      <SessionProvider>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path="/admin/*" element={<AdminRoutes api={adminApi} />} />
          </Routes>
        </MemoryRouter>
      </SessionProvider>
    </ApiProvider>,
  );
}

function adminStreaming() {
  return createApi({ restoreSession: vi.fn().mockResolvedValue(adminAuthTokens) });
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

describe('Admin routes and guard', () => {
  it('redirects /admin to the film library', async () => {
    renderAdmin(createAdminApi(), '/admin', adminStreaming());

    expect(await screen.findByRole('heading', { name: 'Films' })).toBeVisible();
    expect(screen.getByRole('link', { name: /new film/i })).toHaveAttribute('href', '/admin/movies/new');
  });

  it('tells guests to sign in', async () => {
    renderAdmin(createAdminApi(), '/admin', createApi({ restoreSession: vi.fn().mockResolvedValue(null) }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Sign in required');
    expect(screen.queryByRole('heading', { name: 'Films' })).not.toBeInTheDocument();
  });

  it('tells non-admin users they have no access', async () => {
    renderAdmin(createAdminApi(), '/admin', createApi({ restoreSession: vi.fn().mockResolvedValue(authTokens) }));

    expect(await screen.findByRole('alert')).toHaveTextContent("You don't have access");
  });

  it('lets an administrator enter the library', async () => {
    renderAdmin(createAdminApi(), '/admin/movies', adminStreaming());

    expect(await screen.findByRole('heading', { name: 'Films' })).toBeVisible();
    expect(await screen.findByText('Starlight Archive')).toBeVisible();
  });

  it('revokes access when a refresh demotes the administrator', async () => {
    renderAdmin(createAdminApi(), '/admin/movies', adminStreaming());

    expect(await screen.findByRole('heading', { name: 'Films' })).toBeVisible();

    act(() => notifyAuthUser(user));

    expect(await screen.findByRole('alert')).toHaveTextContent("You don't have access");
    expect(screen.queryByRole('heading', { name: 'Films' })).not.toBeInTheDocument();
  });

  it('opens the navigation drawer, moves focus and closes with Escape', async () => {
    renderAdmin(createAdminApi(), '/admin/movies', adminStreaming());
    await screen.findByText('Starlight Archive');

    const toggle = screen.getByLabelText('Open navigation') as HTMLButtonElement;
    fireEvent.click(toggle);

    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));
    expect(screen.getByLabelText('Close navigation')).toHaveFocus();

    fireEvent.keyDown(document, { key: 'Escape' });

    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));
    expect(toggle).toHaveFocus();
  });

  it('closes the drawer and restores focus when a nav link is followed', async () => {
    renderAdmin(createAdminApi(), '/admin/movies', adminStreaming());
    await screen.findByText('Starlight Archive');

    const toggle = screen.getByLabelText('Open navigation') as HTMLButtonElement;
    fireEvent.click(toggle);
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'));

    fireEvent.click(screen.getByRole('link', { name: /films/i }));

    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'));
    expect(toggle).toHaveFocus();
  });
});

describe('Film library', () => {
  it('filters the loaded page by search term', async () => {
    const api = createAdminApi({
      listMovies: vi.fn().mockResolvedValue({
        items: [
          adminMovie,
          { ...adminMovie, id: '00000000-0000-0000-0000-000000000002', title: 'Other Film', slug: 'other-film' },
        ],
        page: 0,
        size: 20,
        total: 2,
      }),
    });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies', adminStreaming());

    await screen.findByText('Other Film');
    await actor.type(screen.getByRole('searchbox', { name: /search films/i }), 'starlight');

    expect(screen.getByText('Starlight Archive')).toBeVisible();
    expect(screen.queryByText('Other Film')).not.toBeInTheDocument();
  });

  it('restores filters from the URL', async () => {
    const publishedFilm = {
      ...adminMovie,
      id: '00000000-0000-0000-0000-000000000002',
      title: 'Published Film',
      slug: 'published-film',
      lifecycle: 'PUBLISHED' as const,
    };
    const api = createAdminApi({
      listMovies: vi.fn().mockResolvedValue({ items: [adminMovie, publishedFilm], page: 0, size: 20, total: 2 }),
    });
    renderAdmin(api, '/admin/movies?lifecycle=PUBLISHED', adminStreaming());

    expect(await screen.findByText('Published Film')).toBeVisible();
    expect(screen.getByLabelText(/lifecycle/i)).toHaveValue('PUBLISHED');
    expect(screen.queryByText('Starlight Archive')).not.toBeInTheDocument();
  });

  it('filters by active media', async () => {
    const active = {
      ...adminMovie,
      id: '00000000-0000-0000-0000-000000000003',
      title: 'Active Film',
      slug: 'active-film',
      activeMediaVersionId: '30000000-0000-0000-0000-000000000001',
    };
    const api = createAdminApi({
      listMovies: vi.fn().mockResolvedValue({ items: [adminMovie, active], page: 0, size: 100, total: 2 }),
    });
    renderAdmin(api, '/admin/movies?media=active', adminStreaming());

    expect(await screen.findByText('Active Film')).toBeVisible();
    expect(screen.getByLabelText(/active media/i)).toHaveValue('active');
    expect(screen.queryByText('Starlight Archive')).not.toBeInTheDocument();
  });

  it('searches across the whole catalog, not just the current page', async () => {
    const target = {
      ...adminMovie,
      id: '00000000-0000-0000-0000-000000000099',
      title: 'Hidden Gem',
      slug: 'hidden-gem',
    };
    const listMovies = vi.fn().mockImplementation(async (query: { page?: number }) => {
      const page = query?.page ?? 0;
      if (page === 0) {
        const items = Array.from({ length: 20 }, (_, index) => ({
          ...adminMovie,
          id: `film-${index}`,
          title: `Film ${index}`,
          slug: `film-${index}`,
        }));
        return { items, page: 0, size: 100, total: 21 };
      }
      return { items: [target], page: 1, size: 100, total: 21 };
    });
    const api = createAdminApi({ listMovies });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies', adminStreaming());

    await screen.findByText('Film 0');
    await actor.type(screen.getByRole('searchbox', { name: /search films/i }), 'hidden');

    expect(await screen.findByText('Hidden Gem')).toBeVisible();
    expect(screen.queryByText('Film 0')).not.toBeInTheDocument();
  });

  it('shows an empty state when there are no films', async () => {
    const api = createAdminApi({
      listMovies: vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, total: 0 }),
    });
    renderAdmin(api, '/admin/movies', adminStreaming());

    expect(await screen.findByText(/no films/i)).toBeVisible();
  });

  it('shows an error state and retries', async () => {
    const listMovies = vi.fn()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue({ items: [adminMovie], page: 0, size: 20, total: 1 });
    const api = createAdminApi({ listMovies });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies', adminStreaming());

    expect(await screen.findByRole('alert')).toBeVisible();
    await actor.click(screen.getByRole('button', { name: /try again/i }));

    expect(await screen.findByText('Starlight Archive')).toBeVisible();
  });

  it('sorts the catalog by title', async () => {
    const alpha = { ...adminMovie, id: 'a1', title: 'Alpha', slug: 'alpha', updatedAt: '2026-01-01T00:00:00.000Z' };
    const bravo = { ...adminMovie, id: 'b2', title: 'Bravo', slug: 'bravo', updatedAt: '2026-01-02T00:00:00.000Z' };
    const api = createAdminApi({
      listMovies: vi.fn().mockResolvedValue({ items: [bravo, alpha], page: 0, size: 100, total: 2 }),
    });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies', adminStreaming());

    await screen.findByText('Bravo');
    await actor.selectOptions(screen.getByLabelText(/sort/i), 'title-asc');

    const rows = screen.getAllByRole('row');
    expect(rows[1]).toHaveTextContent('Alpha');
    expect(rows[2]).toHaveTextContent('Bravo');
  });

  it('paginates the catalog and persists the page in the URL', async () => {
    const items = Array.from({ length: 25 }, (_, index) => ({
      ...adminMovie,
      id: `film-${String(index).padStart(2, '0')}`,
      title: `Film ${String(index).padStart(2, '0')}`,
      slug: `film-${String(index).padStart(2, '0')}`,
    }));
    const api = createAdminApi({
      listMovies: vi.fn().mockResolvedValue({ items, page: 0, size: 100, total: 25 }),
    });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies', adminStreaming());

    await screen.findByText('Film 00');
    expect(screen.getByText('Page 1 of 2')).toBeVisible();

    await actor.click(screen.getByRole('button', { name: /next/i }));
    expect(await screen.findByText('Film 20')).toBeVisible();
    expect(screen.getByText('Page 2 of 2')).toBeVisible();
  });

  it('clamps an out-of-range page to the last valid page', async () => {
    renderAdmin(createAdminApi(), '/admin/movies?page=99', adminStreaming());

    expect(await screen.findByText('Starlight Archive')).toBeVisible();
    expect(screen.getByText('Page 1 of 1')).toBeVisible();
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });
});

describe('Movie editor', () => {
  it('creates a draft with an idempotency key and returns to the library', async () => {
    const api = createAdminApi();
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies/new', adminStreaming());

    await screen.findByRole('heading', { name: 'New film' });
    await actor.type(screen.getByLabelText('Title'), 'My unsaved title');
    await actor.type(screen.getByLabelText('Slug'), 'my-unsaved-title');
    await actor.click(screen.getByRole('button', { name: /^save draft$/i }));

    await waitFor(() => expect(api.createMovie).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'My unsaved title', slug: 'my-unsaved-title' }),
      expect.any(String),
    ));
    expect(await screen.findByRole('heading', { name: 'Films' })).toBeVisible();
  });

  it('rejects a release year outside 1888-2200 without saving', async () => {
    const api = createAdminApi();
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies/new', adminStreaming());

    await screen.findByRole('heading', { name: 'New film' });
    await actor.type(screen.getByLabelText('Title'), 'Year test');
    await actor.type(screen.getByLabelText('Slug'), 'year-test');
    const year = screen.getByLabelText('Release year');
    await actor.clear(year);
    await actor.type(year, '1800');
    await actor.click(screen.getByRole('button', { name: /save and continue/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Release year must be between 1888 and 2200');
    expect(api.createMovie).not.toHaveBeenCalled();
  });

  it('loads a movie and genres and updates with the current revision', async () => {
    const api = createAdminApi();
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${adminMovie.id}`, adminStreaming());

    expect(await screen.findByDisplayValue('Starlight Archive')).toBeVisible();
    await waitFor(() => expect(api.getMovie).toHaveBeenCalledWith(adminMovie.id, expect.anything()));
    await waitFor(() => expect(api.listGenres).toHaveBeenCalled());

    await actor.clear(screen.getByLabelText('Title'));
    await actor.type(screen.getByLabelText('Title'), 'Renamed');
    await actor.click(screen.getByRole('button', { name: /save and continue/i }));

    await waitFor(() => expect(api.updateMovie).toHaveBeenCalledWith(
      adminMovie.id,
      expect.objectContaining({ title: 'Renamed' }),
      adminMovie.revision,
    ));
    expect(await screen.findByText(/changes saved/i)).toBeVisible();
  });

  it('locks the slug once the film has been published', async () => {
    const published = { ...adminMovie, lifecycle: 'PUBLISHED' as const, firstPublishedAt: '2026-09-10T00:00:00.000Z' };
    const api = createAdminApi({ getMovie: vi.fn().mockResolvedValue({ data: published, revision: 9 }) });
    renderAdmin(api, `/admin/movies/${published.id}`, adminStreaming());

    await screen.findByDisplayValue('Starlight Archive');
    expect(screen.getByLabelText('Slug')).toBeDisabled();
  });

  it('renders archived films read-only', async () => {
    const archived = { ...adminMovie, lifecycle: 'ARCHIVED' as const };
    const api = createAdminApi({ getMovie: vi.fn().mockResolvedValue({ data: archived, revision: 10 }) });
    renderAdmin(api, `/admin/movies/${archived.id}`, adminStreaming());

    await screen.findByDisplayValue('Starlight Archive');
    expect(screen.getByLabelText('Title')).toBeDisabled();
    expect(screen.getByRole('button', { name: /^save draft$/i })).toBeDisabled();
  });

  it('keeps unsaved input after a conflict and reloads the latest after confirmation', async () => {
    const api = createAdminApi({
      updateMovie: vi.fn().mockRejectedValue(new RevisionConflictError(problem(412, 'STALE_REVISION'))),
    });
    const actor = userEvent.setup();
    renderAdmin(api, `/admin/movies/${adminMovie.id}`, adminStreaming());

    await screen.findByDisplayValue('Starlight Archive');
    await actor.clear(screen.getByLabelText('Title'));
    await actor.type(screen.getByLabelText('Title'), 'My unsaved title');
    await actor.click(screen.getByRole('button', { name: /save and continue/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This film was updated by another administrator');
    expect(screen.getByLabelText('Title')).toHaveValue('My unsaved title');

    await actor.click(screen.getByRole('button', { name: /reload latest/i }));
    await actor.click(await screen.findByRole('button', { name: /discard and reload/i }));

    await waitFor(() => expect(api.getMovie).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('reloads the latest after a conflict on a just-created draft', async () => {
    const createdId = '00000000-0000-0000-0000-000000000099';
    const api = createAdminApi({
      createMovie: vi.fn().mockImplementation(async (input: { title: string; slug: string }) => ({
        data: { ...adminMovie, id: createdId, title: input.title, slug: input.slug },
        revision: 1,
      })),
      updateMovie: vi.fn().mockRejectedValue(new RevisionConflictError(problem(412, 'STALE_REVISION'))),
    });
    const actor = userEvent.setup();
    renderAdmin(api, '/admin/movies/new', adminStreaming());

    await screen.findByRole('heading', { name: 'New film' });
    await actor.type(screen.getByLabelText('Title'), 'Fresh title');
    await actor.type(screen.getByLabelText('Slug'), 'fresh-title');
    await actor.click(screen.getByRole('button', { name: /save and continue/i }));

    await waitFor(() => expect(api.createMovie).toHaveBeenCalled());
    expect(await screen.findByDisplayValue('Fresh title')).toBeVisible();

    await actor.clear(screen.getByLabelText('Title'));
    await actor.type(screen.getByLabelText('Title'), 'Updated title');
    await actor.click(screen.getByRole('button', { name: /save and continue/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('This film was updated by another administrator');
    expect(screen.getByLabelText('Title')).toHaveValue('Updated title');

    await actor.click(screen.getByRole('button', { name: /reload latest/i }));
    await actor.click(await screen.findByRole('button', { name: /discard and reload/i }));

    await waitFor(() => expect(api.getMovie).toHaveBeenCalledWith(createdId));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
