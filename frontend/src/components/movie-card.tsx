import { Check, Info, Plus } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { MovieSummary } from '../api/streaming-api';

const fallbackPoster = '/artwork/poster-placeholder.svg';

interface MovieCardProps {
  movie: MovieSummary;
  action?: 'add' | 'remove';
  onAction?: (movie: MovieSummary) => void;
  busy?: boolean;
}

export function MovieCard({ movie, action, onAction, busy = false }: MovieCardProps) {
  return (
    <article className="movie-card">
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
        <span className="movie-card__info"><Info aria-hidden="true" size={18} /></span>
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

export function MovieRail({ title, movies }: { title: string; movies: Array<MovieSummary> }) {
  return (
    <section className="movie-rail">
      <h2>{title}</h2>
      <div className="movie-rail__track">
        {movies.map((movie) => <MovieCard key={movie.id} movie={movie} />)}
      </div>
    </section>
  );
}
