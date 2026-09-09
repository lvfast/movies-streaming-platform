import { AlertTriangle, RotateCcw } from 'lucide-react';
import { ProblemError } from '../api/streaming-api';

export function LoadingState({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="loading-state" role="status" aria-live="polite">
      <span className="loading-state__spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const problem = error instanceof ProblemError ? error.problem : null;
  return (
    <section className="error-state" role="alert">
      <AlertTriangle aria-hidden="true" size={28} />
      <div>
        <h2>Something interrupted the show</h2>
        <p>{problem?.detail ?? 'This content could not be loaded. Please try again.'}</p>
        {problem?.requestId && problem.requestId !== 'unavailable' ? (
          <small>Reference: {problem.requestId}</small>
        ) : null}
      </div>
      {onRetry ? (
        <button className="button button--ghost" type="button" onClick={onRetry}>
          <RotateCcw aria-hidden="true" size={17} />
          Try again
        </button>
      ) : null}
    </section>
  );
}
