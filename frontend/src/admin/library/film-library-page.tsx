import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Plus, Search, SlidersHorizontal } from 'lucide-react';
import { useAdminApi } from '../../api/api-context';
import type { AdminMovie } from '../../api/admin-api';
import { ErrorState } from '../../components/feedback';
import { FilmTable, mediaStateOf } from './film-table';

const PAGE_SIZE = 20;
const FETCH_SIZE = 100;

const LIFECYCLES = ['DRAFT', 'PUBLISHED', 'UNPUBLISHED', 'ARCHIVED'] as const;

type SortKey = 'updatedAt-desc' | 'title-asc' | 'title-desc';

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

async function fetchAllMovies(
  api: { listMovies(query: { page?: number; size?: number }, signal?: AbortSignal): Promise<{ items: AdminMovie[]; total: number }> },
  signal: AbortSignal,
): Promise<AdminMovie[]> {
  const all: AdminMovie[] = [];
  let page = 0;
  while (true) {
    const result = await api.listMovies({ page, size: FETCH_SIZE }, signal);
    all.push(...result.items);
    if (all.length >= result.total || result.items.length === 0) break;
    page += 1;
  }
  return all;
}

export function FilmLibraryPage() {
  const api = useAdminApi();
  const [searchParams, setSearchParams] = useSearchParams();

  const q = searchParams.get('q') ?? '';
  const lifecycle = searchParams.get('lifecycle') ?? '';
  const media = searchParams.get('media') ?? '';
  const sort = (searchParams.get('sort') as SortKey) || 'updatedAt-desc';
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0);

  const [movies, setMovies] = useState<AdminMovie[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setError(null);
    setMovies(null);
    fetchAllMovies(api, controller.signal)
      .then((items) => {
        if (active) setMovies(items);
      })
      .catch((err) => {
        if (active && !isAbort(err)) setError(err);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, reloadToken]);

  const filtered = useMemo(() => {
    if (!movies) return [];
    let rows = movies;
    if (q) {
      const needle = q.toLowerCase();
      rows = rows.filter((movie) =>
        movie.title.toLowerCase().includes(needle) || movie.slug.toLowerCase().includes(needle));
    }
    if (lifecycle) rows = rows.filter((movie) => movie.lifecycle === lifecycle);
    if (media) rows = rows.filter((movie) => mediaStateOf(movie) === media);
    return [...rows].sort((a, b) => {
      if (sort === 'title-asc') return a.title.localeCompare(b.title);
      if (sort === 'title-desc') return b.title.localeCompare(a.title);
      return b.updatedAt.localeCompare(a.updatedAt) || a.id.localeCompare(b.id);
    });
  }, [movies, q, lifecycle, media, sort]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);
  const pageMovies = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE);

  function updateParam(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setSearchParams(next);
  }

  useEffect(() => {
    if (page === safePage) return;
    const next = new URLSearchParams(searchParams);
    next.set('page', String(safePage));
    setSearchParams(next);
  }, [page, safePage, searchParams, setSearchParams]);

  return (
    <main className="admin-page">
      <header className="admin-page__header">
        <h1>Films</h1>
        <Link className="button button--primary" to="/admin/movies/new">
          <Plus aria-hidden="true" size={17} />
          New film
        </Link>
      </header>

      <div className="admin-toolbar">
        <label className="admin-search">
          <Search aria-hidden="true" size={16} />
          <input
            aria-label="Search films"
            onChange={(event) => updateParam('q', event.target.value)}
            placeholder="Search by title or slug"
            type="search"
            value={q}
          />
        </label>

        <label className="admin-filter">
          <SlidersHorizontal aria-hidden="true" size={16} />
          <select
            aria-label="Filter by lifecycle"
            onChange={(event) => updateParam('lifecycle', event.target.value)}
            value={lifecycle}
          >
            <option value="">All lifecycles</option>
            {LIFECYCLES.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>

        <label className="admin-filter">
          <select
            aria-label="Filter by active media"
            onChange={(event) => updateParam('media', event.target.value)}
            value={media}
          >
            <option value="">All media</option>
            <option value="active">Active</option>
            <option value="none">None</option>
          </select>
        </label>

        <label className="admin-filter">
          <select
            aria-label="Sort films"
            onChange={(event) => updateParam('sort', event.target.value)}
            value={sort}
          >
            <option value="updatedAt-desc">Recently updated</option>
            <option value="title-asc">Title A–Z</option>
            <option value="title-desc">Title Z–A</option>
          </select>
        </label>
      </div>

      {error ? (
        <ErrorState error={error} onRetry={() => setReloadToken((token) => token + 1)} />
      ) : !movies ? (
        <div className="admin-skeleton" role="status" aria-label="Loading films">
          {Array.from({ length: 5 }, (_, index) => (
            <div className="admin-skeleton__row" key={index}>
              <span className="admin-skeleton__cell" />
              <span className="admin-skeleton__cell" />
              <span className="admin-skeleton__cell" />
              <span className="admin-skeleton__cell" />
              <span className="admin-skeleton__cell" />
              <span className="admin-skeleton__cell" />
            </div>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <section className="admin-empty">
          <p>{movies.length === 0 ? 'No films yet.' : 'No films match the current filters.'}</p>
          {movies.length === 0 ? (
            <Link className="button button--primary" to="/admin/movies/new">Create the first film</Link>
          ) : (
            <button
              className="button button--ghost"
              type="button"
              onClick={() => setSearchParams(new URLSearchParams())}
            >
              Clear filters
            </button>
          )}
        </section>
      ) : (
        <>
          <FilmTable movies={pageMovies} />
          <nav className="admin-pagination" aria-label="Pagination">
            <button
              className="button button--ghost"
              type="button"
              disabled={safePage === 0}
              onClick={() => updateParam('page', String(safePage - 1))}
            >
              Previous
            </button>
            <span>Page {safePage + 1} of {totalPages}</span>
            <button
              className="button button--ghost"
              type="button"
              disabled={safePage + 1 >= totalPages}
              onClick={() => updateParam('page', String(safePage + 1))}
            >
              Next
            </button>
          </nav>
        </>
      )}
    </main>
  );
}
