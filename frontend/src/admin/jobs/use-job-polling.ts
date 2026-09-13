import { useCallback, useEffect, useRef, useState } from 'react';
import type { JobView } from '../../api/admin-api';

const TERMINAL_STATES: ReadonlySet<JobView['state']> = new Set(['SUCCEEDED', 'FAILED']);

export const DEFAULT_POLL_INTERVAL_MS = 3_000;
export const HIDDEN_POLL_INTERVAL_MS = 15_000;

export type JobFetcher = (jobId: string, signal?: AbortSignal) => Promise<JobView>;

export interface JobPollingOptions {
  intervalMs?: number;
  hiddenIntervalMs?: number;
}

export interface JobPollingResult {
  job: JobView | null;
  error: unknown;
  loading: boolean;
  refresh: () => void;
}

function isTerminal(job: JobView): boolean {
  return TERMINAL_STATES.has(job.state);
}

/**
 * Polls one job while the document is visible and the job is nonterminal, slows down while the
 * document is hidden, and stops on a terminal state or unmount. Completion is derived from the
 * job state, never from a 100% progress value.
 */
export function useJobPolling(
  fetchJob: JobFetcher,
  jobId: string | null,
  options: JobPollingOptions = {},
): JobPollingResult {
  const intervalMs = options.intervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  const hiddenIntervalMs = options.hiddenIntervalMs ?? HIDDEN_POLL_INTERVAL_MS;
  const [job, setJob] = useState<JobView | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [nonce, setNonce] = useState(0);
  const fetchRef = useRef(fetchJob);
  fetchRef.current = fetchJob;

  useEffect(() => {
    if (!jobId) {
      setJob(null);
      return undefined;
    }
    let active = true;
    let stopped = false;
    let running = false;
    let timer: number | undefined;
    let controller: AbortController | null = null;

    const clearTimer = () => {
      if (timer !== undefined) {
        window.clearTimeout(timer);
        timer = undefined;
      }
    };

    const schedule = () => {
      clearTimer();
      if (!active || stopped || running) return;
      const hidden = typeof document !== 'undefined' && document.hidden;
      timer = window.setTimeout(tick, hidden ? hiddenIntervalMs : intervalMs);
    };

    async function tick(): Promise<void> {
      if (!active || stopped || running) return;
      running = true;
      timer = undefined;
      controller = new AbortController();
      try {
        const next = await fetchRef.current(jobId as string, controller.signal);
        if (!active) return;
        setJob(next);
        setError(null);
        if (isTerminal(next)) {
          stopped = true;
          return;
        }
      } catch (caught) {
        if (!active || stopped) return;
        setError(caught);
      } finally {
        running = false;
      }
      schedule();
    }

    const onVisibility = () => {
      // A visibility change only re-times the next poll. It must never restart a stopped job or
      // start a second loop while a request is still in flight.
      schedule();
    };

    void tick();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      active = false;
      clearTimer();
      controller?.abort();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [jobId, intervalMs, hiddenIntervalMs, nonce]);

  const refresh = useCallback(() => setNonce((value) => value + 1), []);

  return { job, error, loading: jobId != null && job == null && error == null, refresh };
}
