import { useEffect, useState } from 'react';
import { useApi } from '../api/api-context';
import type { MoviePage, MovieSummary } from '../api/streaming-api';
import { ErrorState, LoadingState } from '../components/feedback';
import { MovieCard } from '../components/movie-card';
import { useSession } from '../session/session-context';

export function WatchlistPage() {
  const api = useApi();
  const { status, openAuth } = useSession();
  const [list, setList] = useState<MoviePage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (status !== 'authenticated') return;
    let active = true;
    setError(null);
    api.watchlist(0, 20)
      .then((page) => active && setList(page))
      .catch((caught) => active && setError(caught));
    return () => {
      active = false;
    };
  }, [api, status, attempt]);

  async function remove(movie: MovieSummary) {
    setBusyId(movie.id);
    setError(null);
    try {
      await api.removeFromWatchlist(movie.id);
      setList((current) => current ? {
        ...current,
        items: current.items.filter((item) => item.id !== movie.id),
        total: Math.max(0, current.total - 1),
      } : current);
    } catch (caught) {
      setError(caught);
    } finally {
      setBusyId(null);
    }
  }

  if (status === 'booting') return <main className="page page--center"><LoadingState label="Restoring your session" /></main>;
  if (status === 'guest') {
    return (
      <main className="page page--center">
        <section className="protected-state">
          <p className="eyebrow">Your list follows you</p>
          <h1>Sign in to see your list</h1>
          <p>Save movies now and come back to them later.</p>
          <button className="button button--primary" type="button" onClick={() => openAuth('login')}>Sign in</button>
        </section>
      </main>
    );
  }

  return (
    <main className="page collection-page">
      <p className="eyebrow">Saved for later</p>
      <h1>My list</h1>
      {error ? <ErrorState error={error} onRetry={() => setAttempt((value) => value + 1)} /> : null}
      {!list && !error ? <LoadingState label="Loading your list" /> : null}
      {list?.items.length === 0 ? (
        <section className="empty-state">
          <h2>Your list is waiting</h2>
          <p>Open a movie to add it here.</p>
        </section>
      ) : null}
      {list?.items.length ? (
        <section className="movie-grid" aria-label="My saved movies">
          {list.items.map((movie) => (
            <MovieCard
              action="remove"
              busy={busyId === movie.id}
              key={movie.id}
              movie={movie}
              onAction={(item) => void remove(item)}
            />
          ))}
        </section>
      ) : null}
    </main>
  );
}
