import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useAdminApi } from '../../api/api-context';
import {
  RevisionConflictError,
  type AdminMovie,
  type Genre,
  type MovieInput,
} from '../../api/admin-api';
import { ProblemError } from '../../api/streaming-api';
import { ErrorState, LoadingState } from '../../components/feedback';
import { MovieForm, type SaveAction } from './movie-form';
import { RevisionConflict } from './revision-conflict';

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function newRequestKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `req-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function messageOf(error: unknown): string {
  if (error instanceof ProblemError) return error.problem.detail;
  return 'The film could not be saved. Please try again.';
}

export function MovieEditorPage() {
  const { movieId } = useParams();
  const navigate = useNavigate();
  const api = useAdminApi();

  const isNew = !movieId;

  const [movie, setMovie] = useState<AdminMovie | null>(null);
  const [revision, setRevision] = useState(0);
  const [genres, setGenres] = useState<Genre[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [loadToken, setLoadToken] = useState(0);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<unknown>(null);
  const [conflict, setConflict] = useState<RevisionConflictError | null>(null);
  const [saveNotice, setSaveNotice] = useState<string | null>(null);

  const requestKey = useMemo(() => newRequestKey(), []);

  useEffect(() => {
    if (movieId && movie?.id === movieId) return;
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    Promise.all([
      api.listGenres(controller.signal),
      movieId ? api.getMovie(movieId, controller.signal) : Promise.resolve(null),
    ]).then(([genreList, versioned]) => {
      if (!active) return;
      setGenres(genreList);
      if (versioned) {
        setMovie(versioned.data);
        setRevision(versioned.revision);
      }
      setLoading(false);
    }).catch((err) => {
      if (!active) return;
      if (!isAbort(err)) setLoadError(err);
      setLoading(false);
    });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, movieId, loadToken]);

  const handleSave = useCallback(async (input: MovieInput, action: SaveAction) => {
    setSaving(true);
    setSaveError(null);
    setConflict(null);
    setSaveNotice(null);
    try {
      const result = isNew
        ? await api.createMovie(input, requestKey)
        : await api.updateMovie(movieId as string, input, revision);
      setMovie(result.data);
      setRevision(result.revision);
      if (isNew) {
        if (action === 'draft') {
          navigate('/admin/movies');
        } else {
          navigate(`/admin/movies/${result.data.id}`, { replace: true });
          setSaveNotice('Changes saved.');
        }
      } else if (action === 'draft') {
        navigate('/admin/movies');
      } else {
        setSaveNotice('Changes saved.');
      }
    } catch (err) {
      if (err instanceof RevisionConflictError) {
        setConflict(err);
      } else {
        setSaveError(err);
      }
    } finally {
      setSaving(false);
    }
  }, [api, isNew, movieId, revision, requestKey, navigate]);

  const reloadLatest = useCallback(async () => {
    if (!movieId) return;
    setSaving(true);
    setSaveError(null);
    try {
      const versioned = await api.getMovie(movieId);
      setMovie(versioned.data);
      setRevision(versioned.revision);
      setConflict(null);
    } catch (err) {
      setSaveError(err);
    } finally {
      setSaving(false);
    }
  }, [api, movieId]);

  if (loading) {
    return (
      <main className="admin-page admin-page--center">
        <LoadingState label="Loading film" />
      </main>
    );
  }

  if (loadError) {
    return (
      <main className="admin-page admin-page--center">
        <ErrorState error={loadError} onRetry={() => setLoadToken((token) => token + 1)} />
      </main>
    );
  }

  const readOnly = movie?.lifecycle === 'ARCHIVED';
  const slugLocked = movie != null && movie.firstPublishedAt != null;

  return (
    <main className="admin-page">
      <header className="admin-page__header">
        <h1>{isNew ? 'New film' : movie?.title}</h1>
      </header>

      {conflict ? <RevisionConflict onReload={() => void reloadLatest()} /> : null}
      {saveError && !conflict ? (
        <p className="admin-form__error" role="alert">{messageOf(saveError)}</p>
      ) : null}
      {saveNotice ? (
        <p className="admin-form__notice" role="status">{saveNotice}</p>
      ) : null}

      <MovieForm
        key={revision}
        initial={movie}
        genres={genres}
        readOnly={readOnly}
        slugLocked={slugLocked}
        saving={saving}
        onSubmit={handleSave}
      />
    </main>
  );
}
