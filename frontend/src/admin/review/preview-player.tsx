import { useEffect, useRef, useState } from 'react';
import type { ManagedPlayback } from '../../api/admin-api';
import type { MediaTokenResponse } from '../../api/streaming-api';
import { attachPrivateHls } from '../../playback/private-hls';

export interface PreviewPlayerProps {
  playback: ManagedPlayback;
  renew: () => Promise<MediaTokenResponse>;
}

/**
 * ADMIN preview transport. It reuses the P5 private HLS transport and deliberately never writes
 * viewing progress: previewing a candidate version must not disturb a viewer's resume position.
 */
export function PreviewPlayer({ playback, renew }: PreviewPlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return undefined;
    setError(null);
    try {
      const transport = attachPrivateHls({
        video,
        playback,
        renew,
        onFatalError: (caught) => setError(caught),
      });
      return () => transport.destroy();
    } catch (caught) {
      setError(caught);
      return undefined;
    }
  }, [playback, renew]);

  return (
    <section className="admin-preview" aria-label="Media preview">
      <video
        aria-label="Preview player"
        className="admin-preview__video"
        controls
        playsInline
        ref={videoRef}
      />
      {error ? (
        <p className="admin-form__error" role="alert">
          The preview stream could not be loaded.
        </p>
      ) : null}
    </section>
  );
}
