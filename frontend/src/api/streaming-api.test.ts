import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { client } from './generated/client.gen';
import { accessTokenStore } from './token-store';
import { streamingApi } from './streaming-api';
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
});

function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType },
  });
}
