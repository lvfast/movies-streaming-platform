import Hls from 'hls.js';
import type { MediaTokenResponse, Playback } from '../api/streaming-api';
import {
  createMediaTokenSession,
  type ManagedPlayback,
  type MediaTokenSession,
} from './media-token-session';

export type MediaTokenRenewal = () => Promise<MediaTokenResponse>;

export interface AttachPrivateHlsOptions {
  video: HTMLVideoElement;
  playback: ManagedPlayback;
  renew: MediaTokenRenewal;
  clock?: () => number;
  onFatalError?: (error: Error) => void;
}

export interface PrivateHlsTransport {
  readonly hls: Hls;
  readonly tokenSession: MediaTokenSession;
  destroy(): void;
  dispose(): void;
}

const FATAL_ERROR_MESSAGE = 'The video stream could not be loaded.';
const UNAUTHORIZED_ERROR_MESSAGE = 'The playback session is no longer authorized.';

/**
 * Narrows a grant to the managed (private, token-protected) shape. Legacy local
 * fixture grants carry none of the managed fields and keep their old behavior.
 */
export function isManagedPlayback(playback: Playback): playback is ManagedPlayback {
  const candidate = playback as Partial<ManagedPlayback> | null | undefined;
  if (!candidate || typeof candidate !== 'object') return false;
  return (
    typeof candidate.sessionId === 'string'
    && typeof candidate.mediaVersionId === 'string'
    && typeof candidate.mediaToken === 'string'
    && typeof candidate.mediaTokenExpiresAt === 'string'
  );
}

/**
 * Routes a managed grant through HLS.js with a media bearer header that is only
 * ever sent to the configured media origin (never to the API origin).
 *
 * Native HLS support is deliberately ignored here: private playback always needs
 * the token-aware HLS.js transport.
 */
export function attachPrivateHls(options: AttachPrivateHlsOptions): PrivateHlsTransport {
  const { video, playback, renew, clock = Date.now, onFatalError } = options;
  const mediaOrigin = configuredMediaOrigin();
  const manifestSource = managedManifestUrl(playback.manifestUrl, mediaOrigin);
  const tokenSession = createMediaTokenSession(playback, renew, clock);

  const createXhrSetup = (getToken: () => Promise<string>) => async (
    xhr: XMLHttpRequest,
    url: string,
  ): Promise<void> => {
    if (!mediaOrigin || requestOrigin(url) !== mediaOrigin) return;
    const token = await getToken();
    xhr.setRequestHeader('Authorization', `Bearer ${token}`);
  };

  const hls = new Hls({
    enableWorker: true,
    xhrSetup: createXhrSetup(() => tokenSession.getToken()),
  });

  let destroyed = false;
  let retriedAfterUnauthorized = false;

  function reportFatal(error: Error): void {
    if (!destroyed) onFatalError?.(error);
  }

  async function retryAfterUnauthorized(): Promise<void> {
    try {
      tokenSession.invalidate();
      // Fetch the renewed token before restarting so the retry carries it. The
      // setup keeps resolving through the session, which refreshes single-flight
      // ahead of the expiry skew for the rest of the playback.
      await tokenSession.getToken();
      if (destroyed) return;
      // hls.js captures `config.xhrSetup` when it builds a loader, so re-binding
      // it before the retry guarantees the restarted load sees the renewed token.
      hls.config.xhrSetup = createXhrSetup(() => tokenSession.getToken());
      // A fatal 401 on the manifest is only re-requested by re-triggering the
      // source load (`startLoad()` alone never reloads the manifest), which also
      // cancels any in-flight loader before the single retry starts.
      hls.loadSource(manifestSource);
      hls.startLoad();
    } catch (error) {
      reportFatal(error instanceof Error ? error : new Error(FATAL_ERROR_MESSAGE));
    }
  }

  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (!data.fatal) return;
    if (data.response?.code === 401) {
      if (retriedAfterUnauthorized) {
        // Exactly one renewal retry is allowed; a second expiry is terminal.
        reportFatal(new Error(UNAUTHORIZED_ERROR_MESSAGE));
        return;
      }
      retriedAfterUnauthorized = true;
      void retryAfterUnauthorized();
      return;
    }
    reportFatal(new Error(FATAL_ERROR_MESSAGE));
  });

  hls.loadSource(manifestSource);
  hls.attachMedia(video);

  function destroy(): void {
    if (destroyed) return;
    destroyed = true;
    tokenSession.dispose();
    hls.destroy();
  }

  return { hls, tokenSession, destroy, dispose: destroy };
}

/**
 * Absolute manifest URL for a managed grant. The backend answers a root-relative
 * HLS path so the same grant works from any origin, but private media is served by
 * the media gateway, so the path is joined onto the configured media origin rather
 * than onto the page origin.
 */
export function resolveMediaManifestUrl(manifestUrl: string, mediaOrigin: string): string {
  if (!mediaOrigin || !manifestUrl.startsWith('/')) return manifestUrl;
  return new URL(manifestUrl, mediaOrigin).toString();
}

/**
 * A managed grant always carries a media token, so it can only be played from the
 * configured media origin. A missing configuration is reported here instead of
 * degrading into an unauthorized request against the API host.
 */
function managedManifestUrl(manifestUrl: string, mediaOrigin: string): string {
  if (!mediaOrigin) {
    throw new Error(
      'VITE_MEDIA_BASE_URL must be set to the media gateway origin before protected playback.',
    );
  }
  return resolveMediaManifestUrl(manifestUrl, mediaOrigin);
}

function configuredMediaOrigin(): string {
  const configured: string | undefined = import.meta.env.VITE_MEDIA_BASE_URL;
  if (!configured) return '';
  try {
    return new URL(configured, window.location.href).origin;
  } catch {
    return '';
  }
}

function requestOrigin(url: string): string {
  try {
    return new URL(url, window.location.href).origin;
  } catch {
    return '';
  }
}
