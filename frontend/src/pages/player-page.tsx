import Hls from 'hls.js';
import { ArrowLeft, RotateCcw } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useApi } from '../api/api-context';
import type { PlaybackGrant, ProgressPayload } from '../api/streaming-api';
import { ErrorState, LoadingState } from '../components/feedback';
import { attachPrivateHls, isManagedPlayback } from '../playback/private-hls';
import { useSession } from '../session/session-context';

export function PlayerPage() {
  const { movieId = '' } = useParams();
  const api = useApi();
  const navigate = useNavigate();
  const { status, openAuth } = useSession();
  const videoRef = useRef<HTMLVideoElement>(null);
  const lastSentPosition = useRef<number | null>(null);
  const [metadata, setMetadata] = useState<PlaybackGrant | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);
  const [resumed, setResumed] = useState(false);

  useEffect(() => {
    if (status !== 'authenticated') return;
    let active = true;
    setMetadata(null);
    setError(null);
    setResumed(false);
    api.playback(movieId)
      .then((playback) => active && setMetadata(playback))
      .catch((caught) => active && setError(caught));
    return () => {
      active = false;
    };
  }, [api, movieId, status, attempt]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !metadata) return;

    if (isManagedPlayback(metadata)) {
      // Managed grants always use the token-aware HLS.js transport, even where
      // the browser could play HLS natively (native playback cannot send the
      // media bearer header).
      try {
        const transport = attachPrivateHls({
          video,
          playback: metadata,
          renew: () => api.mediaToken(metadata.sessionId),
          onFatalError: (caught) => setError(caught),
        });
        return () => transport.destroy();
      } catch (caught) {
        // A misconfigured media origin must surface as an error, never as a silent
        // unauthorized request against the API host.
        setError(caught);
        return undefined;
      }
    }

    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = metadata.manifestUrl;
      return;
    }

    if (!Hls.isSupported()) {
      setError(new Error('HLS playback is not supported in this browser.'));
      return;
    }

    const hls = new Hls({ enableWorker: true });
    hls.loadSource(metadata.manifestUrl);
    hls.attachMedia(video);
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data.fatal) setError(new Error('The video stream could not be loaded.'));
    });
    return () => hls.destroy();
  }, [api, metadata]);

  const sendProgress = useCallback(async (force = false) => {
    const video = videoRef.current;
    if (!video || !metadata || !Number.isFinite(video.duration) || video.duration <= 0) return;
    const durationSeconds = Math.max(1, Math.floor(video.duration));
    const positionSeconds = Math.min(durationSeconds, Math.max(0, Math.floor(video.currentTime)));
    if (!force && lastSentPosition.current === positionSeconds) return;
    lastSentPosition.current = positionSeconds;
    const progress: ProgressPayload = {
      positionSeconds,
      durationSeconds,
      clientUpdatedAt: new Date().toISOString(),
      ...(isManagedPlayback(metadata)
        ? { sessionId: metadata.sessionId, mediaVersionId: metadata.mediaVersionId }
        : {}),
    };
    try {
      await api.updateProgress(metadata.movieId, progress);
    } catch (caught) {
      setError(caught);
    }
  }, [api, metadata]);

  useEffect(() => {
    if (!metadata) return;
    const heartbeat = window.setInterval(() => {
      const video = videoRef.current;
      if (video && !video.paused && !video.ended) void sendProgress();
    }, 15_000);
    return () => window.clearInterval(heartbeat);
  }, [metadata, sendProgress]);

  useEffect(() => {
    const flush = () => void sendProgress(true);
    const flushWhenHidden = () => {
      if (document.visibilityState === 'hidden') flush();
    };
    window.addEventListener('pagehide', flush);
    document.addEventListener('visibilitychange', flushWhenHidden);
    return () => {
      window.removeEventListener('pagehide', flush);
      document.removeEventListener('visibilitychange', flushWhenHidden);
    };
  }, [sendProgress]);

  function applyResumePosition() {
    const video = videoRef.current;
    if (!video || !metadata || resumed) return;
    const resumeAt = Math.min(metadata.resumePositionSeconds, Math.max(0, video.duration || metadata.resumePositionSeconds));
    if (resumeAt > 0) video.currentTime = resumeAt;
    setResumed(true);
  }

  if (status === 'booting') return <main className="player-page player-page--center"><LoadingState label="Preparing your session" /></main>;
  if (status === 'guest') {
    return (
      <main className="player-page player-page--center">
        <section className="protected-state">
          <p className="eyebrow">Playback is ready</p>
          <h1>Sign in to start watching</h1>
          <p>Your place will be saved as you watch.</p>
          <button className="button button--primary" type="button" onClick={() => openAuth('login')}>Sign in</button>
        </section>
      </main>
    );
  }

  if (error && !metadata) {
    return <main className="player-page player-page--center"><ErrorState error={error} onRetry={() => setAttempt((value) => value + 1)} /></main>;
  }
  if (!metadata) return <main className="player-page player-page--center"><LoadingState label="Preparing the stream" /></main>;

  return (
    <main className="player-page">
      <button className="icon-button player-page__back" type="button" onClick={() => navigate(-1)} aria-label="Leave player">
        <ArrowLeft aria-hidden="true" />
      </button>
      <video
        aria-label="Video player"
        autoPlay
        className="video-player"
        controls
        onEnded={() => void sendProgress(true)}
        onLoadedMetadata={applyResumePosition}
        onPause={() => void sendProgress(true)}
        playsInline
        ref={videoRef}
      />
      {metadata.resumePositionSeconds > 0 && resumed ? (
        <div className="resume-toast" role="status">
          <RotateCcw aria-hidden="true" size={17} />
          Resumed from {formatTime(metadata.resumePositionSeconds)}
        </div>
      ) : null}
      {error ? <div className="player-page__error"><ErrorState error={error} /></div> : null}
    </main>
  );
}

function formatTime(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}
