import { Info, Play } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from '../api/api-context';
import type { CatalogHome, MovieDetails, MovieSummary } from '../api/streaming-api';
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
  const [savedMovieIds, setSavedMovieIds] = useState<Set<string>>(new Set());
  const [watchlistBusyId, setWatchlistBusyId] = useState<string | null>(null);
  const detailRequests = useRef(new Map<string, Promise<MovieDetails>>());

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

  useEffect(() => {
    if (status !== 'authenticated') {
      setSavedMovieIds(new Set());
      return;
    }
    let active = true;
    api.watchlist(0, 100)
      .then((page) => {
        if (active) setSavedMovieIds(new Set(page.items.map((movie) => movie.id)));
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [api, status]);

  const loadDetails = useCallback((movie: MovieSummary) => {
    const cached = detailRequests.current.get(movie.id);
    if (cached) return cached;
    const request = api.movieBySlug(movie.slug).catch((caught) => {
      detailRequests.current.delete(movie.id);
      throw caught;
    });
    detailRequests.current.set(movie.id, request);
    return request;
  }, [api]);

  const playDetails = useCallback((movie: MovieDetails) => {
    if (status !== 'authenticated') {
      openAuth('login');
      return;
    }
    if (movie.playable) navigate(`/watch/${movie.id}`);
  }, [navigate, openAuth, status]);

  const startMovie = useCallback(async (movie: MovieSummary) => {
    if (status !== 'authenticated') {
      openAuth('login');
      return;
    }
    setStartingMovie(true);
    try {
      const details = await loadDetails(movie);
      navigate(details.playable ? `/watch/${details.id}` : `/title/${details.slug}`);
    } catch (caught) {
      setError(caught);
    } finally {
      setStartingMovie(false);
    }
  }, [loadDetails, navigate, openAuth, status]);

  const toggleWatchlist = useCallback(async (movie: MovieSummary) => {
    if (status !== 'authenticated') {
      openAuth('login');
      return;
    }
    const saved = savedMovieIds.has(movie.id);
    setWatchlistBusyId(movie.id);
    try {
      if (saved) await api.removeFromWatchlist(movie.id);
      else await api.addToWatchlist(movie.id);
      setSavedMovieIds((current) => {
        const next = new Set(current);
        if (saved) next.delete(movie.id);
        else next.add(movie.id);
        return next;
      });
    } catch (caught) {
      setError(caught);
    } finally {
      setWatchlistBusyId(null);
    }
  }, [api, openAuth, savedMovieIds, status]);

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
          <MovieRail
            key={rail.key}
            title={rail.title}
            movies={rail.items}
            preview={{
              busyMovieId: watchlistBusyId,
              loadDetails,
              onPlay: playDetails,
              onToggleWatchlist: (movie) => void toggleWatchlist(movie),
              savedMovieIds,
            }}
            variant={rail.key === 'featured' ? 'featured' : 'default'}
          />
        ))}
      </div>
      <footer className="site-footer">
        <div className="site-footer__brand">
          <img alt="" className="site-footer__logo" src="/logo/logo.png" />
          <span>LVFAST CINEMA</span>
        </div>
        <div className="site-footer__body">
          <p className="site-footer__about">
            A personal streaming library — hand-picked films, series and highlights streaming in HD.
            Pick up where you left off with resume playback and a watchlist that stays with you.
          </p>
          <p className="site-footer__contact">
            Contact: <a href="mailto:vinhphatluu23@gmail.com">vinhphatluu23@gmail.com</a>
            <span aria-hidden="true"> · </span>
            <a href="https://github.com/lvfast" rel="noreferrer" target="_blank">github.com/lvfast</a>
          </p>
        </div>
      </footer>
    </main>
  );
}
