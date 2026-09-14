import { Link } from 'react-router-dom';
import type { AdminMovie } from '../../api/admin-api';

export type MediaState = 'active' | 'none';

export function mediaStateOf(movie: AdminMovie): MediaState {
  return movie.activeMediaVersionId ? 'active' : 'none';
}

const LIFECYCLE_LABELS: Record<AdminMovie['lifecycle'], string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  UNPUBLISHED: 'Unpublished',
  ARCHIVED: 'Archived',
};

export function LifecycleBadge({ lifecycle }: { lifecycle: AdminMovie['lifecycle'] }) {
  return (
    <span className={`admin-badge admin-badge--${lifecycle.toLowerCase()}`}>
      {LIFECYCLE_LABELS[lifecycle]}
    </span>
  );
}

export function MediaBadge({ state }: { state: MediaState }) {
  return (
    <span className={`admin-badge admin-badge--media-${state}`}>
      {state === 'active' ? 'Active' : 'None'}
    </span>
  );
}

function formatDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

export function FilmTable({ movies }: { movies: AdminMovie[] }) {
  return (
    <table className="admin-table">
      <thead>
        <tr>
          <th scope="col">Title</th>
          <th scope="col">Lifecycle</th>
          <th scope="col">Active media</th>
          <th scope="col" className="admin-table__num">Released</th>
          <th scope="col" className="admin-table__num">Revision</th>
          <th scope="col" className="admin-table__num">Updated</th>
        </tr>
      </thead>
      <tbody>
        {movies.map((movie) => (
          <tr key={movie.id}>
            <td>
              <Link className="admin-table__title" to={`/admin/movies/${movie.id}`}>
                {movie.title}
                <span className="admin-table__slug">{movie.slug}</span>
              </Link>
            </td>
            <td><LifecycleBadge lifecycle={movie.lifecycle} /></td>
            <td><MediaBadge state={mediaStateOf(movie)} /></td>
            <td className="admin-table__num">{movie.releaseYear}</td>
            <td className="admin-table__num">{movie.revision}</td>
            <td className="admin-table__num">{formatDate(movie.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
