import { Check, Info, Play, Plus } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Link } from 'react-router-dom';
import type { MovieDetails, MovieSummary } from '../api/streaming-api';

const fallbackPoster = '/artwork/poster-placeholder.svg';
const fallbackBackdrop = '/artwork/backdrop-placeholder.svg';

interface MovieCardProps {
  movie: MovieSummary;
  action?: 'add' | 'remove';
  onAction?: (movie: MovieSummary) => void;
  busy?: boolean;
  onPreviewEnter?: (movie: MovieSummary, element: HTMLElement) => void;
  onPreviewLeave?: () => void;
}

export function MovieCard({ movie, action, onAction, busy = false, onPreviewEnter, onPreviewLeave }: MovieCardProps) {
  return (
    <article
      className="movie-card"
      onPointerEnter={(event) => onPreviewEnter?.(movie, event.currentTarget)}
      onPointerLeave={() => onPreviewLeave?.()}
    >
      <Link className="movie-card__art" to={`/title/${movie.slug}`} aria-label={`More information about ${movie.title}`}>
        <img
          alt=""
          loading="lazy"
          onError={(event) => {
            if (event.currentTarget.src.endsWith(fallbackPoster)) return;
            event.currentTarget.src = fallbackPoster;
          }}
          src={movie.posterUrl}
        />
      </Link>
      <div className="movie-card__body">
        <div>
          <h3>{movie.title}</h3>
          <p>{movie.releaseYear} · {movie.maturityRating}</p>
        </div>
        {action && onAction ? (
          <button
            aria-label={`${action === 'add' ? 'Add' : 'Remove'} ${movie.title} ${action === 'add' ? 'to' : 'from'} my list`}
            className="icon-button movie-card__action"
            disabled={busy}
            onClick={() => onAction(movie)}
            type="button"
          >
            {action === 'add' ? <Plus aria-hidden="true" /> : <Check aria-hidden="true" />}
          </button>
        ) : null}
      </div>
    </article>
  );
}

interface MovieRailProps {
  title: string;
  movies: Array<MovieSummary>;
  variant?: 'default' | 'featured';
  preview?: {
    loadDetails: (movie: MovieSummary) => Promise<MovieDetails>;
    onPlay?: (movie: MovieDetails) => void;
    onToggleWatchlist?: (movie: MovieSummary) => void;
    savedMovieIds?: ReadonlySet<string>;
    busyMovieId?: string | null;
  };
}

export function MovieRail({ title, movies, variant = 'default', preview }: MovieRailProps) {
  const headingId = useId();
  const drag = useRef({ active: false, moved: false, startX: 0, startScrollLeft: 0, lastX: 0, velocity: 0 });
  const scrollFrame = useRef<number | null>(null);
  const inertiaFrame = useRef<number | null>(null);
  const queuedScroll = useRef<{ element: HTMLDivElement; left: number } | null>(null);
  const openTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const exitTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewRequest = useRef(0);
  const [dragging, setDragging] = useState(false);
  const [previewClosing, setPreviewClosing] = useState(false);
  const [previewCard, setPreviewCard] = useState<{
    movie: MovieSummary;
    details: MovieDetails | null;
    rect: DOMRect;
  } | null>(null);
  const featured = variant === 'featured';

  const stopAnimation = () => {
    if (scrollFrame.current !== null) cancelAnimationFrame(scrollFrame.current);
    if (inertiaFrame.current !== null) cancelAnimationFrame(inertiaFrame.current);
    scrollFrame.current = null;
    inertiaFrame.current = null;
    queuedScroll.current = null;
  };

  const flushQueuedScroll = () => {
    if (!queuedScroll.current) return;
    queuedScroll.current.element.scrollLeft = queuedScroll.current.left;
    queuedScroll.current = null;
    if (scrollFrame.current !== null) cancelAnimationFrame(scrollFrame.current);
    scrollFrame.current = null;
  };

  const startInertia = (element: HTMLDivElement) => {
    let velocity = drag.current.velocity;
    const step = () => {
      if (Math.abs(velocity) < 0.35) {
        inertiaFrame.current = null;
        setDragging(false);
        return;
      }
      const previous = element.scrollLeft;
      element.scrollLeft += velocity;
      velocity *= 0.92;
      if (element.scrollLeft === previous) {
        inertiaFrame.current = null;
        setDragging(false);
        return;
      }
      inertiaFrame.current = requestAnimationFrame(step);
    };
    inertiaFrame.current = requestAnimationFrame(step);
  };

  const clearPreviewTimers = () => {
    if (openTimer.current) clearTimeout(openTimer.current);
    if (closeTimer.current) clearTimeout(closeTimer.current);
    if (exitTimer.current) clearTimeout(exitTimer.current);
    openTimer.current = null;
    closeTimer.current = null;
    exitTimer.current = null;
  };

  const closePreview = () => {
    clearPreviewTimers();
    previewRequest.current += 1;
    setPreviewClosing(true);
    exitTimer.current = setTimeout(() => {
      setPreviewCard(null);
      setPreviewClosing(false);
      exitTimer.current = null;
    }, 180);
  };

  const schedulePreview = (movie: MovieSummary, element: HTMLElement) => {
    if (!preview) return;
    if (window.matchMedia?.('(hover: hover) and (pointer: fine)').matches === false) return;
    clearPreviewTimers();
    openTimer.current = setTimeout(() => {
      const request = previewRequest.current + 1;
      previewRequest.current = request;
      setPreviewClosing(false);
      setPreviewCard({ movie, details: null, rect: element.getBoundingClientRect() });
      void preview.loadDetails(movie).then((details) => {
        if (previewRequest.current !== request) return;
        setPreviewCard((current) => current?.movie.id === movie.id ? { ...current, details } : current);
      }).catch(() => undefined);
    }, 350);
  };

  const schedulePreviewClose = () => {
    if (openTimer.current) clearTimeout(openTimer.current);
    openTimer.current = null;
    closeTimer.current = setTimeout(closePreview, 150);
  };

  useEffect(() => {
    window.addEventListener('scroll', closePreview, true);

    return () => {
      window.removeEventListener('scroll', closePreview, true);
      stopAnimation();
      clearPreviewTimers();
    };
  }, []);

  return (
    <section
      aria-labelledby={headingId}
      className={`movie-rail${featured ? ' movie-rail--featured' : ''}`}
    >
      <h2 id={headingId}>{title}</h2>
      <div
        className={`movie-rail__track${dragging ? ' movie-rail__track--dragging' : ''}`}
        onClickCapture={(event) => {
          if (drag.current.moved) event.preventDefault();
          drag.current.moved = false;
        }}
        onDragStart={(event) => event.preventDefault()}
        onPointerDown={(event) => {
          if (event.button !== 0) return;
          closePreview();
          stopAnimation();
          drag.current = {
            active: true,
            moved: false,
            startX: event.clientX,
            startScrollLeft: event.currentTarget.scrollLeft,
            lastX: event.clientX,
            velocity: 0,
          };
        }}
        onPointerMove={(event) => {
          if (!drag.current.active) return;
          const distance = event.clientX - drag.current.startX;
          if (!drag.current.moved && Math.abs(distance) > 5) {
            drag.current.moved = true;
            event.currentTarget.setPointerCapture?.(event.pointerId);
            setDragging(true);
          }
          if (!drag.current.moved) return;
          const delta = drag.current.lastX - event.clientX;
          drag.current.velocity = Math.max(-45, Math.min(45, delta));
          drag.current.lastX = event.clientX;
          queuedScroll.current = {
            element: event.currentTarget,
            left: drag.current.startScrollLeft - distance,
          };
          if (scrollFrame.current === null) {
            scrollFrame.current = requestAnimationFrame(() => {
              scrollFrame.current = null;
              if (!queuedScroll.current) return;
              queuedScroll.current.element.scrollLeft = queuedScroll.current.left;
              queuedScroll.current = null;
            });
          }
        }}
        onPointerUp={(event) => {
          drag.current.active = false;
          if (drag.current.moved) {
            flushQueuedScroll();
            event.currentTarget.releasePointerCapture?.(event.pointerId);
            startInertia(event.currentTarget);
          } else {
            setDragging(false);
          }
        }}
        onPointerCancel={() => {
          stopAnimation();
          drag.current.active = false;
          setDragging(false);
        }}
        onWheel={() => {
          stopAnimation();
          setDragging(false);
        }}
      >
        {movies.map((movie) => (
          <MovieCard
            key={movie.id}
            movie={movie}
            onPreviewEnter={schedulePreview}
            onPreviewLeave={schedulePreviewClose}
          />
        ))}
      </div>
      {previewCard ? createPortal(
        <aside
          aria-hidden={previewClosing}
          aria-label={previewCard.movie.title}
          className={`movie-preview${previewClosing ? ' movie-preview--closing' : ''}`}
          onPointerEnter={clearPreviewTimers}
          onPointerLeave={schedulePreviewClose}
          role="dialog"
          style={{
            left: Math.max(16, Math.min(window.innerWidth - 436, previewCard.rect.left + previewCard.rect.width / 2 - 210)),
            top: Math.max(72, Math.min(window.innerHeight - 426, previewCard.rect.top - previewCard.rect.height * 0.2)),
          }}
        >
          <img
            alt=""
            className="movie-preview__backdrop"
            onError={(event) => {
              if (event.currentTarget.src.endsWith(fallbackBackdrop)) return;
              event.currentTarget.src = fallbackBackdrop;
            }}
            src={previewCard.movie.backdropUrl}
          />
          <div className="movie-preview__body">
            <h3>{previewCard.movie.title}</h3>
            <div className="movie-preview__actions">
              <button
                aria-label={`Play ${previewCard.movie.title}`}
                className="button button--light movie-preview__play"
                disabled={!previewCard.details?.playable}
                onClick={() => {
                  const details = previewCard.details;
                  closePreview();
                  if (details) preview?.onPlay?.(details);
                }}
                type="button"
              >
                <Play aria-hidden="true" fill="currentColor" />
                Play
              </button>
              <button
                aria-label={`${preview?.savedMovieIds?.has(previewCard.movie.id) ? 'Remove' : 'Add'} ${previewCard.movie.title} ${preview?.savedMovieIds?.has(previewCard.movie.id) ? 'from' : 'to'} my list`}
                className="button button--glass movie-preview__icon-action"
                disabled={preview?.busyMovieId === previewCard.movie.id}
                onClick={() => {
                  const movie = previewCard.movie;
                  closePreview();
                  preview?.onToggleWatchlist?.(movie);
                }}
                type="button"
              >
                {preview?.savedMovieIds?.has(previewCard.movie.id) ? <Check aria-hidden="true" /> : <Plus aria-hidden="true" />}
              </button>
              <Link
                aria-label={`More information about ${previewCard.movie.title}`}
                className="button button--glass movie-preview__details"
                onClick={closePreview}
                to={`/title/${previewCard.movie.slug}`}
              >
                <Info aria-hidden="true" />
                Details
              </Link>
            </div>
            <p className="movie-preview__meta">
              <span className="maturity-badge">{previewCard.movie.maturityRating}</span>
              <span>{previewCard.movie.releaseYear}</span>
              <span>{Math.ceil(previewCard.movie.runtimeSeconds / 60)} min</span>
            </p>
            <p className="movie-preview__genres">{previewCard.movie.genres.join(' · ')}</p>
            {previewCard.details ? <p className="movie-preview__synopsis">{previewCard.details.synopsis}</p> : null}
          </div>
        </aside>,
        document.body,
      ) : null}
    </section>
  );
}
