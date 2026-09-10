import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiProvider } from './api/api-context';
import { ProblemError } from './api/streaming-api';
import { authTokens, catalog, createApi, page, user } from './test/fixtures';

function renderApp(api = createApi(), route = '/') {
  return render(
    <ApiProvider api={api}>
      <MemoryRouter initialEntries={[route]}>
        <App />
      </MemoryRouter>
    </ApiProvider>,
  );
}

describe('App integration shell', () => {
  it('introduces the cinema before entering the catalog', () => {
    renderApp(createApi(), '/');

    expect(screen.getByRole('heading', { level: 1, name: /stories worth staying for/i })).toBeVisible();
    expect(screen.getByRole('link', { name: /browse movies/i })).toHaveAttribute('href', '/browse');
    expect(screen.queryByRole('navigation', { name: /primary navigation/i })).not.toBeInTheDocument();
  });

  it('shows the catalog hero and rails to guests at the browse route', async () => {
    renderApp(createApi(), '/browse');

    expect(await screen.findByRole('heading', { level: 1, name: 'Starlight Archive' })).toBeVisible();
    expect(screen.getByRole('heading', { name: 'Featured tonight' })).toBeVisible();
    expect(screen.getByRole('navigation', { name: /primary navigation/i })).toBeVisible();
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeVisible();
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Accounts and viewing history may be reset.');
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Use a unique password and avoid personal information.');
  });

  it('asks a guest to sign in when saving from a movie preview', async () => {
    const api = createApi();
    renderApp(api, '/browse');

    await screen.findByRole('heading', { level: 1, name: 'Starlight Archive' });
    fireEvent.pointerEnter(screen.getByRole('article'));
    const preview = await screen.findByRole('dialog', { name: 'Starlight Archive' });
    fireEvent.click(within(preview).getByRole('button', { name: /add starlight archive to my list/i }));

    expect(await screen.findByRole('dialog', { name: /welcome back/i })).toBeVisible();
    expect(screen.queryByRole('dialog', { name: 'Starlight Archive' })).not.toBeInTheDocument();
    expect(api.addToWatchlist).not.toHaveBeenCalled();
  });

  it('signs in and keeps the authenticated identity in the shell', async () => {
    const api = createApi();
    const actor = userEvent.setup();
    renderApp(api, '/browse');

    await screen.findByRole('heading', { level: 1, name: 'Starlight Archive' });
    await actor.click(screen.getByRole('button', { name: /^sign in$/i }));
    const signInDialog = within(screen.getByRole('dialog'));
    await actor.type(signInDialog.getByLabelText(/username/i), 'viewer_one');
    await actor.type(signInDialog.getByLabelText(/^password/i), 'a-secure-password');
    await actor.click(signInDialog.getByRole('button', { name: /^sign in$/i }));

    await waitFor(() => expect(api.login).toHaveBeenCalledWith({
      username: 'viewer_one',
      password: 'a-secure-password',
    }));
    expect(await screen.findByText('viewer_one')).toBeVisible();
    expect(screen.getByRole('link', { name: /my list/i })).toBeVisible();
  });

  it('creates a demo account from the same auth dialog', async () => {
    const api = createApi();
    const actor = userEvent.setup();
    renderApp(api, '/browse');

    await screen.findByRole('heading', { level: 1, name: 'Starlight Archive' });
    await actor.click(screen.getByRole('button', { name: /^sign in$/i }));
    await actor.click(within(screen.getByRole('dialog')).getByRole('button', { name: /create an account/i }));
    const registerDialog = within(screen.getByRole('dialog'));
    await actor.type(registerDialog.getByLabelText(/username/i), 'viewer_one');
    await actor.type(registerDialog.getByLabelText(/^password/i), 'a-secure-password');
    await actor.click(registerDialog.getByRole('button', { name: /^create account$/i }));

    await waitFor(() => expect(api.register).toHaveBeenCalledWith({
      username: 'viewer_one',
      password: 'a-secure-password',
    }));
    expect(await screen.findByText('viewer_one')).toBeVisible();
  });

  it('shows stable Problem Details errors in the auth dialog', async () => {
    const api = createApi({
      login: vi.fn().mockRejectedValue(new ProblemError({
        type: 'about:blank',
        title: 'Unauthorized',
        status: 401,
        detail: 'The username or password is incorrect.',
        requestId: 'request-123',
        code: 'INVALID_CREDENTIALS',
      })),
    });
    const actor = userEvent.setup();
    renderApp(api, '/browse');

    await screen.findByRole('heading', { level: 1, name: 'Starlight Archive' });
    await actor.click(screen.getByRole('button', { name: /^sign in$/i }));
    const signInDialog = within(screen.getByRole('dialog'));
    await actor.type(signInDialog.getByLabelText(/username/i), 'viewer_one');
    await actor.type(signInDialog.getByLabelText(/^password/i), 'wrong-password');
    await actor.click(signInDialog.getByRole('button', { name: /^sign in$/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The username or password is incorrect.');
    expect(screen.getByRole('alert')).toHaveTextContent('request-123');
  });

  it('searches the catalog from the global navigation', async () => {
    const api = createApi();
    const actor = userEvent.setup();
    renderApp(api, '/browse');

    await screen.findByRole('heading', { level: 1, name: 'Starlight Archive' });
    await actor.click(screen.getByRole('button', { name: /search/i }));
    await actor.type(screen.getByRole('searchbox'), 'stars{enter}');

    await waitFor(() => expect(api.search).toHaveBeenCalledWith('stars', 0, 20));
    expect(await screen.findByRole('heading', { name: /results for “stars”/i })).toBeVisible();
  });

  it('loads an authenticated watchlist and removes an item', async () => {
    const api = createApi({
      restoreSession: vi.fn().mockResolvedValue(authTokens),
      catalogHome: vi.fn().mockResolvedValue(catalog),
      watchlist: vi.fn().mockResolvedValue(page),
    });
    const actor = userEvent.setup();
    renderApp(api, '/my-list');

    expect(await screen.findByRole('heading', { name: 'My list' })).toBeVisible();
    await actor.click(await screen.findByRole('button', { name: /remove starlight archive from my list/i }));

    await waitFor(() => expect(api.removeFromWatchlist).toHaveBeenCalledWith(
      '00000000-0000-0000-0000-000000000001',
    ));
  });

  it('adds a title to the authenticated watchlist from its detail page', async () => {
    const api = createApi({
      restoreSession: vi.fn().mockResolvedValue(authTokens),
      watchlist: vi.fn().mockResolvedValue({ ...page, items: [], total: 0 }),
    });
    const actor = userEvent.setup();
    renderApp(api, '/title/starlight-archive');

    await screen.findByRole('heading', { name: 'Starlight Archive' });
    await actor.click(screen.getByRole('button', { name: /^my list$/i }));

    await waitFor(() => expect(api.addToWatchlist).toHaveBeenCalledWith(
      '00000000-0000-0000-0000-000000000001',
    ));
    expect(screen.getByRole('button', { name: /in my list/i })).toBeVisible();
  });

  it('asks guests to sign in before protected playback', async () => {
    renderApp(createApi(), '/watch/00000000-0000-0000-0000-000000000001');

    expect(await screen.findByRole('heading', { name: /sign in to start watching/i })).toBeVisible();
    expect(screen.getByRole('button', { name: /sign in/i })).toBeVisible();
  });

  it('resumes playback and sends a progress heartbeat', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably');
    const api = createApi({ restoreSession: vi.fn().mockResolvedValue({ ...authTokens, user }) });
    renderApp(api, '/watch/00000000-0000-0000-0000-000000000001');

    const video = await screen.findByLabelText('Video player') as HTMLVideoElement;
    Object.defineProperty(video, 'duration', { configurable: true, value: 5400 });
    Object.defineProperty(video, 'currentTime', { configurable: true, writable: true, value: 0 });
    fireEvent.loadedMetadata(video);
    expect(video.currentTime).toBe(37);

    video.currentTime = 52.8;
    fireEvent.pause(video);

    await waitFor(() => expect(api.updateProgress).toHaveBeenCalledWith(
      '00000000-0000-0000-0000-000000000001',
      expect.objectContaining({ positionSeconds: 52, durationSeconds: 5400 }),
    ));
  });
});
