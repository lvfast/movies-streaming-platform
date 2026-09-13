import { describe, expect, it, vi } from 'vitest';
import {
  createMediaTokenSession,
  MEDIA_TOKEN_SAFETY_SKEW_MS,
  type ManagedPlayback,
} from './media-token-session';
import type { MediaTokenResponse } from '../api/streaming-api';

const NOW = Date.parse('2026-09-07T00:00:00.000Z');
const clock = () => NOW;

function playbackExpiringAt(expiresAtMs: number, mediaToken = 'initial-token'): ManagedPlayback {
  return {
    movieId: '00000000-0000-0000-0000-000000000001',
    manifestUrl: 'https://media.example.test/hls/movie/version/attempt/index.m3u8',
    resumePositionSeconds: 0,
    sessionId: '40000000-0000-0000-0000-000000000001',
    mediaVersionId: '20000000-0000-0000-0000-000000000001',
    mediaToken,
    mediaTokenExpiresAt: new Date(expiresAtMs).toISOString(),
  };
}

function renewal(mediaToken: string, expiresAtMs = NOW + 300_000): MediaTokenResponse {
  return { mediaToken, mediaTokenExpiresAt: new Date(expiresAtMs).toISOString() };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

describe('createMediaTokenSession', () => {
  it('returns the initial token without renewing while it is fresh', async () => {
    const renew = vi.fn().mockResolvedValue(renewal('unused-token'));
    const session = createMediaTokenSession(playbackExpiringAt(NOW + 60_000), renew, clock);

    await expect(session.getToken()).resolves.toBe('initial-token');
    expect(renew).not.toHaveBeenCalled();
  });

  it('returns the initial token immediately outside the safety skew', async () => {
    const renew = vi.fn().mockResolvedValue(renewal('unused-token'));
    const session = createMediaTokenSession(
      playbackExpiringAt(NOW + MEDIA_TOKEN_SAFETY_SKEW_MS + 1_000),
      renew,
      clock,
    );

    await expect(session.getToken()).resolves.toBe('initial-token');
    expect(renew).not.toHaveBeenCalled();
  });

  it('refreshes when the token is inside the 30-second safety skew', async () => {
    const renew = vi.fn().mockResolvedValue(renewal('skew-renewed-token'));
    const session = createMediaTokenSession(
      playbackExpiringAt(NOW + MEDIA_TOKEN_SAFETY_SKEW_MS - 1_000),
      renew,
      clock,
    );

    await expect(session.getToken()).resolves.toBe('skew-renewed-token');
    expect(renew).toHaveBeenCalledTimes(1);
  });

  it('refreshes an already expired token', async () => {
    const renew = vi.fn().mockResolvedValue(renewal('expired-renewed-token'));
    const session = createMediaTokenSession(playbackExpiringAt(NOW - 1_000), renew, clock);

    await expect(session.getToken()).resolves.toBe('expired-renewed-token');
    expect(renew).toHaveBeenCalledTimes(1);
  });

  it('shares one in-flight renewal across concurrent getToken calls', async () => {
    const gate = deferred<MediaTokenResponse>();
    const renew = vi.fn(() => gate.promise);
    const session = createMediaTokenSession(playbackExpiringAt(NOW - 1_000), renew, clock);

    const calls = Array.from({ length: 5 }, () => session.getToken());
    expect(renew).toHaveBeenCalledTimes(1);

    gate.resolve(renewal('single-flight-token'));
    await expect(Promise.all(calls)).resolves.toEqual(Array(5).fill('single-flight-token'));
    expect(renew).toHaveBeenCalledTimes(1);
  });

  it('forces a refresh on the next getToken call after invalidate()', async () => {
    const renew = vi.fn().mockResolvedValue(renewal('invalidated-renewed-token'));
    const session = createMediaTokenSession(playbackExpiringAt(NOW + 600_000), renew, clock);

    await expect(session.getToken()).resolves.toBe('initial-token');
    expect(renew).not.toHaveBeenCalled();

    session.invalidate();
    await expect(session.getToken()).resolves.toBe('invalidated-renewed-token');
    expect(renew).toHaveBeenCalledTimes(1);
  });

  it('propagates a rejected renewal and retries it on the next getToken call', async () => {
    const failure = new Error('token endpoint unavailable');
    const renew = vi.fn()
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce(renewal('retried-token'));
    const session = createMediaTokenSession(playbackExpiringAt(NOW - 1_000), renew, clock);

    await expect(session.getToken()).rejects.toThrow('token endpoint unavailable');
    await expect(session.getToken()).resolves.toBe('retried-token');
    expect(renew).toHaveBeenCalledTimes(2);
  });

  it('resolves the last known token after dispose() without renewing again', async () => {
    const renew = vi.fn().mockResolvedValue(renewal('never-used-token'));
    const session = createMediaTokenSession(playbackExpiringAt(NOW - 1_000), renew, clock);

    session.dispose();

    await expect(session.getToken()).resolves.toBe('initial-token');
    await expect(session.getToken()).resolves.toBe('initial-token');
    expect(renew).not.toHaveBeenCalled();
  });

  it('ignores a renewal that settles after dispose()', async () => {
    const gate = deferred<MediaTokenResponse>();
    const renew = vi.fn(() => gate.promise);
    const session = createMediaTokenSession(playbackExpiringAt(NOW - 1_000), renew, clock);

    const inFlight = session.getToken();
    session.dispose();
    gate.resolve(renewal('late-token'));

    await expect(inFlight).resolves.toBe('late-token');
    await expect(session.getToken()).resolves.toBe('initial-token');
    expect(renew).toHaveBeenCalledTimes(1);
  });
});
