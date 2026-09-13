import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { RotateCcw } from 'lucide-react';
import { useAdminApi } from '../../api/api-context';
import type { JobView } from '../../api/admin-api';
import { ErrorState } from '../../components/feedback';
import { useJobPolling } from './use-job-polling';

function newRequestKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `retry-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function isRetryable(error: unknown): boolean {
  const status = (error as { problem?: { status?: number } })?.problem?.status;
  return status == null || status === 0 || status >= 500;
}

async function retryWithSameKey<T>(run: () => Promise<T>): Promise<T> {
  try {
    return await run();
  } catch (error) {
    if (!isRetryable(error)) throw error;
    return run();
  }
}

export function JobsPage() {
  const api = useAdminApi();
  const { jobId } = useParams();
  const navigate = useNavigate();

  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [listError, setListError] = useState<unknown>(null);
  const [retrying, setRetrying] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);

  const { job, error: pollError, loading } = useJobPolling(api.getJob, jobId ?? null);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setJobs(null);
    setListError(null);
    api.listJobs({ size: 50 }, controller.signal)
      .then((page) => {
        if (active) setJobs(page.items);
      })
      .catch((error) => {
        if (active && !(error instanceof DOMException && error.name === 'AbortError')) {
          setListError(error);
        }
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, reloadToken]);

  const retry = useCallback(async () => {
    if (!job || retrying) return;
    setRetrying(true);
    setListError(null);
    // One key per retry action: a response-loss retry inside this action reuses it, but a later
    // retry of a different job must never replay the previous action's key.
    const requestKey = newRequestKey();
    try {
      const created = await retryWithSameKey(() => api.retryJob(job.id, requestKey));
      navigate(`/admin/jobs/${created.id}`);
    } catch (error) {
      setListError(error);
    } finally {
      setRetrying(false);
    }
  }, [api, job, navigate, retrying]);

  return (
    <main className="admin-page">
      <header className="admin-page__header">
        <h1>Processing jobs</h1>
      </header>

      {jobId ? (
        loading ? (
          <p role="status">Loading job…</p>
        ) : pollError && !job ? (
          <ErrorState error={pollError} />
        ) : job ? (
          <section className="admin-job-detail" aria-label="Job detail">
            <header className="admin-job-detail__header">
              <h2>{job.kind === 'TRANSCODE' ? 'Video processing' : 'Artwork processing'}</h2>
              <StateBadge state={job.state} />
            </header>
            <dl className="admin-job-detail__meta">
              <div><dt>Attempt</dt><dd>{job.attemptNumber}</dd></div>
              <div><dt>Progress</dt><dd>{job.progressPercent}%</dd></div>
              <div><dt>Stage</dt><dd>{job.stage ?? '—'}</dd></div>
              <div><dt>Updated</dt><dd>{formatInstant(job.updatedAt)}</dd></div>
            </dl>
            {job.state === 'FAILED' ? (
              <div className="admin-job-detail__failure">
                <p>
                  <strong>{job.errorCode ?? 'PROCESSING_FAILED'}</strong>
                  {': '}
                  {job.errorSummary ?? 'Processing failed.'}
                </p>
                <button
                  className="button button--danger"
                  type="button"
                  disabled={retrying}
                  onClick={() => void retry()}
                >
                  <RotateCcw aria-hidden="true" size={16} />
                  {retrying ? 'Retrying…' : 'Retry job'}
                </button>
              </div>
            ) : null}
          </section>
        ) : null
      ) : null}

      {listError ? (
        <ErrorState error={listError} onRetry={() => setReloadToken((token) => token + 1)} />
      ) : !jobs ? (
        <p role="status">Loading jobs…</p>
      ) : jobs.length === 0 ? (
        <section className="admin-empty"><p>No processing jobs yet.</p></section>
      ) : (
        <table className="admin-table">
          <thead>
            <tr>
              <th scope="col">Movie</th>
              <th scope="col">Kind</th>
              <th scope="col">State</th>
              <th scope="col">Progress</th>
              <th scope="col">Attempt</th>
              <th scope="col"><span className="admin-visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((row) => (
              <tr key={row.id}>
                <td className="admin-table__slug">{row.movieId.slice(0, 8)}</td>
                <td>{row.kind === 'TRANSCODE' ? 'Video' : 'Artwork'}</td>
                <td><StateBadge state={row.state} /></td>
                <td className="admin-table__num">{row.progressPercent}%</td>
                <td className="admin-table__num">{row.attemptNumber}</td>
                <td>
                  <Link className="button button--ghost" to={`/admin/jobs/${row.id}`}>Track</Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  );
}

export function StateBadge({ state }: { state: JobView['state'] }) {
  const label = state === 'RETRY_WAIT'
    ? 'Retry wait'
    : state.charAt(0) + state.slice(1).toLowerCase();
  return <span className={`admin-badge admin-badge--job-${state.toLowerCase()}`}>{label}</span>;
}

function formatInstant(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
