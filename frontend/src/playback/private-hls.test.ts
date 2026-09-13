import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaybackGrant } from '../api/streaming-api';
import {
  managedPlayback,
  managedPlaybackWithRelativeManifest,
  playback as legacyPlayback,
} from '../test/fixtures';
import {
  attachPrivateHls,
  isManagedPlayback,
  resolveMediaManifestUrl,
  type AttachPrivateHlsOptions,
} from './private-hls';

const MEDIA_BASE_URL = 'https://media.example.test';
const MEDIA_MANIFEST_URL = `${MEDIA_BASE_URL}/hls/movie/version/attempt/index.m3u8`;
const API_ORIGIN_URL = 'https://api.example.test/api/v1/movies/movie-1/playback';

type FakeXhr = { setRequestHeader: ReturnType<typeof vi.fn> };
type XhrSetup = (xhr: FakeXhr, url: string) => Promise<void> | void;

const hlsMock = vi.hoisted(() => {
  type ErrorHandler = (event: string, data: unknown) => void;

  class MockHls {
    static Events = { ERROR: 'hlsError' };

    static isSupported = () => true;

    static instances: MockHls[] = [];

    config: Record<string, unknown>;

    loadSource: ReturnType<typeof vi.fn>;

    attachMedia: ReturnType<typeof vi.fn>;

    startLoad: ReturnType<typeof vi.fn>;

    stopLoad: ReturnType<typeof vi.fn>;

    destroy: ReturnType<typeof vi.fn>;

    handlers: Map<string, Set<ErrorHandler>>;

    constructor(config: Record<string, unknown> = {}) {
      this.config = config;
      this.loadSource = vi.fn();
      this.attachMedia = vi.fn();
      this.startLoad = vi.fn();
      this.stopLoad = vi.fn();
      this.destroy = vi.fn();
      this.handlers = new Map();
      MockHls.instances.push(this);
    }

    on(event: string, handler: ErrorHandler) {
      const handlers = this.handlers.get(event) ?? new Set<ErrorHandler>();
      handlers.add(handler);
      this.handlers.set(event, handlers);
      return this;
    }

    off(event: string, handler: ErrorHandler) {
      this.handlers.get(event)?.delete(handler);
      return this;
    }

    emit(event: string, data: unknown) {
      this.handlers.get(event)?.forEach((handler) => handler(event, data));
      return this;
    }
  }

  return { MockHls };
});

vi.mock('hls.js', () => ({ default: hlsMock.MockHls }));

type MockHlsInstance = InstanceType<typeof hlsMock.MockHls>;

function attach(overrides: Partial<AttachPrivateHlsOptions> = {}) {
  const video = overrides.video ?? document.createElement('video');
  const renew = vi.fn().mockResolvedValue({
    mediaToken: 'renewed-media-token',
    mediaTokenExpiresAt: '2099-01-01T00:00:00.000Z',
  });
  const transport = attachPrivateHls({
    video,
    playback: managedPlayback,
    renew,
    ...overrides,
  });
  return { transport, hls: transport.hls as unknown as MockHlsInstance, renew, video };
}

function xhrSetupOf(hls: MockHlsInstance): XhrSetup {
  const setup = hls.config.xhrSetup;
  if (typeof setup !== 'function') throw new Error('xhrSetup was not configured');
  return setup as XhrSetup;
}

describe('attachPrivateHls', () => {
  beforeEach(() => {
    hlsMock.MockHls.instances.length = 0;
    vi.stubEnv('VITE_MEDIA_BASE_URL', MEDIA_BASE_URL);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('adds the media bearer header for the configured media origin only', async () => {
    const { hls, renew } = attach();
    const setup = xhrSetupOf(hls);

    const mediaXhr: FakeXhr = { setRequestHeader: vi.fn() };
    await setup(mediaXhr, MEDIA_MANIFEST_URL);
    expect(mediaXhr.setRequestHeader).toHaveBeenCalledWith(
      'Authorization',
      `Bearer ${managedPlayback.mediaToken}`,
    );

    const apiXhr: FakeXhr = { setRequestHeader: vi.fn() };
    await setup(apiXhr, API_ORIGIN_URL);
    expect(apiXhr.setRequestHeader).not.toHaveBeenCalled();

    const lookalikeXhr: FakeXhr = { setRequestHeader: vi.fn() };
    await setup(lookalikeXhr, 'https://media.example.test.evil.test/hls/index.m3u8');
    expect(lookalikeXhr.setRequestHeader).not.toHaveBeenCalled();

    const otherPortXhr: FakeXhr = { setRequestHeader: vi.fn() };
    await setup(otherPortXhr, 'https://media.example.test:8443/hls/index.m3u8');
    expect(otherPortXhr.setRequestHeader).not.toHaveBeenCalled();

    expect(renew).not.toHaveBeenCalled();
  });

  it('refuses managed playback when no media origin is configured', () => {
    vi.stubEnv('VITE_MEDIA_BASE_URL', '');

    expect(() => attach()).toThrow(/VITE_MEDIA_BASE_URL/);
  });

  it('joins a backend-relative manifest path onto the configured media origin', () => {
    const video = document.createElement('video');
    const { hls } = attach({ video, playback: managedPlaybackWithRelativeManifest });

    expect(hls.loadSource).toHaveBeenCalledWith(
      `${MEDIA_BASE_URL}${managedPlaybackWithRelativeManifest.manifestUrl}`,
    );
    expect(hls.attachMedia).toHaveBeenCalledWith(video);
  });

  it('loads the managed manifest into the media element through Hls.js', () => {
    const video = document.createElement('video');
    const { hls } = attach({ video });

    expect(hls.loadSource).toHaveBeenCalledWith(managedPlayback.manifestUrl);
    expect(hls.attachMedia).toHaveBeenCalledWith(video);
    expect(hls.config.enableWorker).toBe(true);
  });

  it('invalidates and retries once with the renewed token after a fatal 401', async () => {
    const { hls, renew } = attach();

    expect(hls.loadSource).toHaveBeenCalledTimes(1);

    hls.emit('hlsError', { fatal: true, response: { code: 401 } });
    await vi.waitFor(() => expect(hls.startLoad).toHaveBeenCalledTimes(1));

    expect(renew).toHaveBeenCalledTimes(1);
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
    expect(hls.loadSource).toHaveBeenLastCalledWith(managedPlayback.manifestUrl);

    const setup = xhrSetupOf(hls);
    const xhr: FakeXhr = { setRequestHeader: vi.fn() };
    await setup(xhr, MEDIA_MANIFEST_URL);
    expect(xhr.setRequestHeader).toHaveBeenCalledWith('Authorization', 'Bearer renewed-media-token');
  });

  it('reports a fatal error after a second 401 instead of retrying again', async () => {
    const onFatalError = vi.fn();
    const { hls, renew } = attach({ onFatalError });

    hls.emit('hlsError', { fatal: true, response: { code: 401 } });
    await vi.waitFor(() => expect(hls.startLoad).toHaveBeenCalledTimes(1));

    hls.emit('hlsError', { fatal: true, response: { code: 401 } });

    expect(onFatalError).toHaveBeenCalledTimes(1);
    expect(onFatalError.mock.calls[0][0]).toBeInstanceOf(Error);
    expect(renew).toHaveBeenCalledTimes(1);
    expect(hls.startLoad).toHaveBeenCalledTimes(1);
    expect(hls.loadSource).toHaveBeenCalledTimes(2);
  });

  it('reports non-401 fatal errors without retrying', () => {
    const onFatalError = vi.fn();
    const { hls } = attach({ onFatalError });

    hls.emit('hlsError', { fatal: false, response: { code: 500 } });
    expect(onFatalError).not.toHaveBeenCalled();

    hls.emit('hlsError', { fatal: true, response: { code: 500 } });
    expect(onFatalError).toHaveBeenCalledTimes(1);
    expect(hls.startLoad).not.toHaveBeenCalled();
  });

  it('routes managed playback through Hls.js even when native HLS support exists', () => {
    const video = document.createElement('video');
    const canPlayType = vi.fn(() => 'probably');
    video.canPlayType = canPlayType as unknown as HTMLMediaElement['canPlayType'];

    attach({ video });

    expect(hlsMock.MockHls.instances).toHaveLength(1);
    expect(canPlayType).not.toHaveBeenCalled();
  });

  it('destroys the Hls instance and disposes the token session', async () => {
    const { transport, hls, renew } = attach();

    transport.destroy();
    expect(hls.destroy).toHaveBeenCalledTimes(1);

    transport.dispose();
    expect(hls.destroy).toHaveBeenCalledTimes(1);

    await expect(transport.tokenSession.getToken()).resolves.toBe(managedPlayback.mediaToken);
    expect(renew).not.toHaveBeenCalled();
  });
});

describe('isManagedPlayback', () => {
  it('accepts a full managed grant', () => {
    expect(isManagedPlayback(managedPlayback)).toBe(true);
  });

  it('rejects the legacy local fixture grant', () => {
    expect(isManagedPlayback(legacyPlayback)).toBe(false);
  });

  it('rejects a partially populated grant', () => {
    const partial: PlaybackGrant = { ...managedPlayback, mediaToken: undefined };
    expect(isManagedPlayback(partial)).toBe(false);
  });
});

describe('resolveMediaManifestUrl', () => {
  it('joins a root-relative path onto the media origin', () => {
    expect(resolveMediaManifestUrl('/hls/movie/version/attempt/index.m3u8', MEDIA_BASE_URL)).toBe(
      `${MEDIA_BASE_URL}/hls/movie/version/attempt/index.m3u8`,
    );
  });

  it('keeps an already absolute manifest URL untouched', () => {
    expect(resolveMediaManifestUrl(MEDIA_MANIFEST_URL, MEDIA_BASE_URL)).toBe(MEDIA_MANIFEST_URL);
    expect(resolveMediaManifestUrl(API_ORIGIN_URL, MEDIA_BASE_URL)).toBe(API_ORIGIN_URL);
  });

  it('keeps a relative path untouched when no media origin is configured', () => {
    expect(resolveMediaManifestUrl('/media/fixtures/index.m3u8', '')).toBe('/media/fixtures/index.m3u8');
  });
});
