import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { client } from './generated/client.gen';
import { accessTokenStore } from './token-store';
import { streamingApi, subscribeAuthUser, type User } from './streaming-api';
import { authTokens, page } from '../test/fixtures';

describe('generated API adapter', () => {
  beforeEach(() => {
    accessTokenStore.clear();
    client.setConfig({ baseUrl: 'http://localhost/api/v1' });
  });

  afterEach(() => {
    accessTokenStore.clear();
    vi.unstubAllGlobals();
  });

  it('adds the in-memory bearer token to protected generated requests', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(page));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('memory-only-token');

    await expect(streamingApi.watchlist()).resolves.toEqual(page);

    const request = fetchMock.mock.calls[0][0] as Request;
    expect(request.headers.get('Authorization')).toBe('Bearer memory-only-token');
    expect(request.credentials).toBe('include');
  });

  it('rotates an expired token through the refresh cookie and retries once', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        type: 'about:blank',
        title: 'Unauthorized',
        status: 401,
        detail: 'The access token has expired.',
        requestId: 'expired-token',
        code: 'UNAUTHORIZED',
      }, 401, 'application/problem+json'))
      .mockResolvedValueOnce(jsonResponse({ ...authTokens, accessToken: 'rotated-token' }))
      .mockResolvedValueOnce(jsonResponse(page));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('expired-token');

    await expect(streamingApi.watchlist()).resolves.toEqual(page);

    expect(fetchMock).toHaveBeenCalledTimes(3);
    const retry = fetchMock.mock.calls[2][0] as Request;
    expect(retry.headers.get('Authorization')).toBe('Bearer rotated-token');
    expect(accessTokenStore.get()).toBe('rotated-token');
  });

  it('notifies subscribers of the refreshed user after a 401 refresh', async () => {
    const demotedUser: User = { ...authTokens.user, roles: ['USER'] };
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        type: 'about:blank',
        title: 'Unauthorized',
        status: 401,
        detail: 'The access token has expired.',
        requestId: 'expired-token',
        code: 'UNAUTHORIZED',
      }, 401, 'application/problem+json'))
      .mockResolvedValueOnce(jsonResponse({ ...authTokens, accessToken: 'rotated-token', user: demotedUser }))
      .mockResolvedValueOnce(jsonResponse(page));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('expired-token');

    const seen: User[] = [];
    const unsubscribe = subscribeAuthUser((next) => seen.push(next));
    await streamingApi.watchlist();
    unsubscribe();

    expect(seen).toEqual([demotedUser]);
  });

  it('renews a managed playback session with an authenticated bodyless POST', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      mediaToken: 'media-token',
      mediaTokenExpiresAt: '2026-09-07T00:15:00.000Z',
    }));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('memory-only-token');

    await expect(streamingApi.mediaToken('session-1')).resolves.toEqual({
      mediaToken: 'media-token',
      mediaTokenExpiresAt: '2026-09-07T00:15:00.000Z',
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/me/playback-sessions/session-1/token');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('include');
    expect(init.body).toBeUndefined();
    expect((init.headers as Headers).get('Authorization')).toBe('Bearer memory-only-token');
  });

  it('rotates the access token when the media token endpoint reports 401', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({
        type: 'about:blank',
        title: 'Unauthorized',
        status: 401,
        detail: 'The access token has expired.',
        requestId: 'expired-token',
        code: 'UNAUTHORIZED',
      }, 401, 'application/problem+json'))
      .mockResolvedValueOnce(jsonResponse({ ...authTokens, accessToken: 'rotated-token' }))
      .mockResolvedValueOnce(jsonResponse({
        mediaToken: 'renewed-media-token',
        mediaTokenExpiresAt: '2026-09-07T00:15:00.000Z',
      }));
    vi.stubGlobal('fetch', fetchMock);
    accessTokenStore.set('expired-token');

    await expect(streamingApi.mediaToken('session-1')).resolves.toEqual({
      mediaToken: 'renewed-media-token',
      mediaTokenExpiresAt: '2026-09-07T00:15:00.000Z',
    });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [, retryInit] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect((retryInit.headers as Headers).get('Authorization')).toBe('Bearer rotated-token');
    expect(accessTokenStore.get()).toBe('rotated-token');
  });
});

function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType },
  });
}
