import { Search } from 'lucide-react';
import { type FormEvent, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useApi } from '../api/api-context';
import type { MoviePage } from '../api/streaming-api';
import { ErrorState, LoadingState } from '../components/feedback';
import { MovieCard } from '../components/movie-card';

export function SearchPage() {
  const api = useApi();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const query = params.get('q')?.trim() ?? '';
  const [draft, setDraft] = useState(query);
  const [results, setResults] = useState<MoviePage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => setDraft(query), [query]);

  useEffect(() => {
    if (!query) {
      setResults(null);
      setError(null);
      return;
    }
    let active = true;
    setResults(null);
    setError(null);
    api.search(query, 0, 20)
      .then((page) => active && setResults(page))
      .catch((caught) => active && setError(caught));
    return () => {
      active = false;
    };
  }, [api, query, attempt]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextQuery = draft.trim();
    if (nextQuery) navigate(`/search?q=${encodeURIComponent(nextQuery)}`);
  }

  return (
    <main className="page search-page">
      <p className="eyebrow">Explore the catalog</p>
      <h1>{query ? `Results for “${query}”` : 'Find your next story'}</h1>
      <form className="search-page__form" onSubmit={submit} role="search">
        <Search aria-hidden="true" />
        <input
          aria-label="Search the catalog"
          autoFocus={!query}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Search by title or genre"
          type="search"
          value={draft}
        />
        <button className="button button--primary" type="submit">Search</button>
      </form>
      {!query ? <p className="search-page__hint">Try a title such as “Starlight” or a genre such as “Drama”.</p> : null}
      {query && !results && !error ? <LoadingState label={`Searching for ${query}`} /> : null}
      {error ? <ErrorState error={error} onRetry={() => setAttempt((value) => value + 1)} /> : null}
      {results?.items.length === 0 ? (
        <section className="empty-state">
          <h2>No matches found</h2>
          <p>Try a broader title or genre.</p>
        </section>
      ) : null}
      {results?.items.length ? (
        <section className="movie-grid" aria-label="Search results">
          {results.items.map((movie) => <MovieCard key={movie.id} movie={movie} />)}
        </section>
      ) : null}
    </main>
  );
}
