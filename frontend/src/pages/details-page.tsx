import { ArrowLeft, Check, Play, Plus } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useApi } from '../api/api-context';
import type { MovieDetails } from '../api/streaming-api';
import { ErrorState, LoadingState } from '../components/feedback';
import { useSession } from '../session/session-context';

const fallbackBackdrop = '/artwork/backdrop-placeholder.svg';

export function DetailsPage() {
  const { slug = '' } = useParams();
  const api = useApi();
  const navigate = useNavigate();
  const { status, openAuth } = useSession();
  const [movie, setMovie] = useState<MovieDetails | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setError(null);
    api.movieBySlug(slug)
      .then((details) => active && setMovie(details))
      .catch((caught) => active && setError(caught));
    return () => {
      active = false;
    };
  }, [api, slug, attempt]);

  useEffect(() => {
    if (!movie || status !== 'authenticated') {
      setSaved(false);
      return;
    }
    let active = true;
    api.watchlist(0, 100)
      .then((page) => active && setSaved(page.items.some((item) => item.id === movie.id)))
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [api, movie, status]);

  async function toggleSaved() {
    if (!movie) return;
    if (status !== 'authenticated') {
      openAuth('login');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (saved) await api.removeFromWatchlist(movie.id);
      else await api.addToWatchlist(movie.id);
      setSaved((value) => !value);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  function play() {
    if (!movie?.playable) return;
    if (status !== 'authenticated') {
      openAuth('login');
      return;
    }
    navigate(`/watch/${movie.id}`);
  }

  if (error && !movie) return <main className="page page--center"><ErrorState error={error} onRetry={() => setAttempt((value) => value + 1)} /></main>;
  if (!movie) return <main className="page page--center"><LoadingState label="Opening title" /></main>;

  return (
    <main className="details-page">
      <img
        alt=""
        className="details-page__backdrop"
        onError={(event) => {
          if (event.currentTarget.src.endsWith(fallbackBackdrop)) return;
          event.currentTarget.src = fallbackBackdrop;
        }}
        src={movie.backdropUrl}
      />
      <div className="details-page__scrim" />
      <button className="icon-button details-page__back" type="button" onClick={() => navigate(-1)} aria-label="Go back">
        <ArrowLeft aria-hidden="true" />
      </button>
      <section className="details-panel">
        <p className="eyebrow">LVFAST original selection</p>
        <h1>{movie.title}</h1>
        <p className="details-panel__meta">
          <span>{movie.releaseYear}</span>
          <span className="maturity-badge">{movie.maturityRating}</span>
          <span>{Math.ceil(movie.runtimeSeconds / 60)} min</span>
        </p>
        <p className="details-panel__synopsis">{movie.synopsis}</p>
        <p className="details-panel__genres"><strong>Genres:</strong> {movie.genres.join(', ')}</p>
        <div className="details-panel__actions">
          <button className="button button--light" disabled={!movie.playable} type="button" onClick={play}>
            <Play aria-hidden="true" fill="currentColor" />
            {movie.playable ? 'Play' : 'Coming soon'}
          </button>
          <button className="button button--glass" disabled={busy} type="button" onClick={() => void toggleSaved()}>
            {saved ? <Check aria-hidden="true" /> : <Plus aria-hidden="true" />}
            {saved ? 'In my list' : 'My list'}
          </button>
        </div>
        {error ? <ErrorState error={error} /> : null}
      </section>
    </main>
  );
}
