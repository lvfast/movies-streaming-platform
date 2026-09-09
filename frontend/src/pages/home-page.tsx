import { Info, Play } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from '../api/api-context';
import type { CatalogHome, MovieSummary } from '../api/streaming-api';
import { ErrorState, LoadingState } from '../components/feedback';
import { MovieRail } from '../components/movie-card';
import { useSession } from '../session/session-context';

const fallbackBackdrop = '/artwork/backdrop-placeholder.svg';

export function HomePage() {
  const api = useApi();
  const navigate = useNavigate();
  const { status, openAuth } = useSession();
  const [catalog, setCatalog] = useState<CatalogHome | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);
  const [startingMovie, setStartingMovie] = useState(false);

  useEffect(() => {
    let active = true;
    setError(null);
    api.catalogHome()
      .then((home) => active && setCatalog(home))
      .catch((caught) => active && setError(caught));
    return () => {
      active = false;
    };
  }, [api, attempt]);

  const startMovie = useCallback(async (movie: MovieSummary) => {
    if (status !== 'authenticated') {
      openAuth('login');
      return;
    }
    setStartingMovie(true);
    try {
      const details = await api.movieBySlug(movie.slug);
      navigate(details.playable ? `/watch/${details.id}` : `/title/${details.slug}`);
    } catch (caught) {
      setError(caught);
    } finally {
      setStartingMovie(false);
    }
  }, [api, navigate, openAuth, status]);

  if (error && !catalog) return <main className="page page--center"><ErrorState error={error} onRetry={() => setAttempt((value) => value + 1)} /></main>;
  if (!catalog) return <main className="page page--center"><LoadingState label="Curating tonight’s catalog" /></main>;

  const featured = catalog.rails.find((rail) => rail.items.length > 0)?.items[0];
  if (!featured) {
    return <main className="page page--center"><section className="empty-state"><h1>Nothing is playing yet</h1><p>Check back when the catalog has been published.</p></section></main>;
  }

  return (
    <main>
      <section className="hero" aria-labelledby="featured-title">
        <img
          alt=""
          className="hero__backdrop"
          onError={(event) => {
            if (event.currentTarget.src.endsWith(fallbackBackdrop)) return;
            event.currentTarget.src = fallbackBackdrop;
          }}
          src={featured.backdropUrl}
        />
        <div className="hero__scrim" />
        <div className="hero__content">
          <p className="eyebrow">Featured tonight</p>
          <h1 id="featured-title">{featured.title}</h1>
          <p className="hero__meta">
            <span>{featured.releaseYear}</span>
            <span className="maturity-badge">{featured.maturityRating}</span>
            <span>{Math.ceil(featured.runtimeSeconds / 60)} min</span>
          </p>
          <p className="hero__genres">{featured.genres.join(' · ')}</p>
          <div className="hero__actions">
            <button className="button button--light" disabled={startingMovie} type="button" onClick={() => void startMovie(featured)}>
              <Play aria-hidden="true" fill="currentColor" />
              {status === 'authenticated' ? 'Play' : 'Sign in to play'}
            </button>
            <button className="button button--glass" type="button" onClick={() => navigate(`/title/${featured.slug}`)}>
              <Info aria-hidden="true" />
              More info
            </button>
          </div>
        </div>
      </section>
      <div className="catalog-rails">
        {catalog.rails.map((rail) => (
          <MovieRail key={rail.key} title={rail.title} movies={rail.items} />
        ))}
      </div>
      <footer className="site-footer">
        <span>DEMO READY</span>
        <p>Public demo catalog. Accounts and viewing history may be reset.</p>
        <p>Use a unique password and avoid personal information.</p>
      </footer>
    </main>
  );
}
