import type { ManagedPlayback, MediaTokenResponse } from '../api/streaming-api';

export type { ManagedPlayback };

/** Refresh the media token this many milliseconds before it actually expires. */
export const MEDIA_TOKEN_SAFETY_SKEW_MS = 30_000;

export interface MediaTokenSession {
  getToken(): Promise<string>;
  invalidate(): void;
  dispose(): void;
}

/**
 * Keeps the short-lived media bearer token fresh for a single managed grant.
 *
 * `getToken()` never issues two concurrent refreshes: callers that arrive while
 * a refresh is in flight share the same promise. A rejected refresh leaves the
 * previous token (and its staleness) untouched, so the next `getToken()` retries.
 *
 * `dispose()` marks the session terminal, abandons any pending refresh state and
 * stops all future refreshes. Afterwards `getToken()` still resolves with the
 * last known token so in-flight media requests settle instead of hanging (it
 * rejects only when no token was ever known, which cannot happen for a managed
 * grant).
 */
export function createMediaTokenSession(
  initial: ManagedPlayback,
  renew: () => Promise<MediaTokenResponse>,
  clock: () => number,
): MediaTokenSession {
  let token: string | null = initial.mediaToken;
  let expiresAtMs = Date.parse(initial.mediaTokenExpiresAt);
  let stale = false;
  let pending: Promise<string> | null = null;
  let disposed = false;

  function needsRefresh(): boolean {
    if (stale || token === null) return true;
    if (!Number.isFinite(expiresAtMs)) return true;
    return expiresAtMs - clock() <= MEDIA_TOKEN_SAFETY_SKEW_MS;
  }

  function apply(renewed: MediaTokenResponse): string {
    token = renewed.mediaToken;
    expiresAtMs = Date.parse(renewed.mediaTokenExpiresAt);
    stale = false;
    return renewed.mediaToken;
  }

  function refresh(): Promise<string> {
    return renew().then((renewed) => (
      // A refresh that settles after dispose() must not resurrect session state.
      disposed ? renewed.mediaToken : apply(renewed)
    ));
  }

  function getToken(): Promise<string> {
    if (disposed) {
      return token === null
        ? Promise.reject(new Error('The media token session has been disposed.'))
        : Promise.resolve(token);
    }
    if (!needsRefresh()) return Promise.resolve(token as string);
    if (!pending) {
      pending = refresh().finally(() => {
        pending = null;
      });
    }
    return pending;
  }

  function invalidate(): void {
    stale = true;
  }

  function dispose(): void {
    disposed = true;
    pending = null;
  }

  return { getToken, invalidate, dispose };
}
