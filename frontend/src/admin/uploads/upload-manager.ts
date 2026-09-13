import type { AdminApi, SignedPart, UploadSession } from '../../api/admin-api';
import { computeResumeFingerprint } from './upload-fingerprint';
import { StorageUploadError, uploadPartToSignedUrl } from './r2-part-uploader';

export const DEFAULT_CONCURRENCY = 4;
const MAX_PART_ATTEMPTS = 3;
const MAX_COMPLETION_ATTEMPTS = 3;

export class FingerprintMismatchError extends Error {
  constructor() {
    super('Resuming an upload requires reselecting the same file');
    this.name = 'FingerprintMismatchError';
  }
}

export class UploadPausedError extends Error {
  constructor() {
    super('The upload was paused');
    this.name = 'UploadPausedError';
  }
}

export interface UploadJournalEntry {
  uploadId: string;
  movieId: string;
  fingerprint: string;
  fileName: string;
  sizeBytes: number;
}

/**
 * Stores only non-sensitive resume metadata. A `File`, login/media token, storage credential or
 * presigned URL is never persisted.
 */
export interface UploadJournal {
  read(): UploadJournalEntry | null;
  write(entry: UploadJournalEntry): void;
  clear(): void;
}

export function browserUploadJournal(storage: Storage = window.localStorage): UploadJournal {
  const key = 'lvfast.admin.upload';
  return {
    read() {
      try {
        const raw = storage.getItem(key);
        return raw ? (JSON.parse(raw) as UploadJournalEntry) : null;
      } catch {
        return null;
      }
    },
    write(entry) {
      storage.setItem(key, JSON.stringify(entry));
    },
    clear() {
      storage.removeItem(key);
    },
  };
}

export interface UploadManager {
  fingerprint(file: File): Promise<string>;
  start(file: File, session: UploadSession): Promise<UploadSession>;
  resume(
    file: File,
    session: UploadSession,
    expectedFingerprint?: string,
  ): Promise<UploadSession>;
  pause(): void;
  abort(): Promise<UploadSession>;
  readonly session: UploadSession | null;
}

export type UploadManagerDependencies = Pick<
  AdminApi,
  'listUploadParts' | 'signUploadParts' | 'completeUpload' | 'abortUpload'
> & {
  uploadPart?: (part: SignedPart, blob: Blob) => Promise<string | null>;
  fingerprint?: (file: File) => Promise<string>;
  concurrency?: number;
  journal?: UploadJournal;
  requestKey?: () => string;
  /** Reports the coarse phase so a page can show "Uploading" then "Finalizing". */
  onPhase?: (phase: 'uploading' | 'finalizing') => void;
};

function newRequestKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `upload-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function isRetryableCompletion(error: unknown): boolean {
  if (error instanceof StorageUploadError) return false;
  const status = (error as { problem?: { status?: number } })?.problem?.status;
  if (status == null) return true;
  return status === 0 || status === 408 || status === 429 || status >= 500;
}

export function createUploadManager(dependencies: UploadManagerDependencies): UploadManager {
  const uploadPart = dependencies.uploadPart ?? uploadPartToSignedUrl;
  const fingerprintOf = dependencies.fingerprint ?? ((file: File) => computeResumeFingerprint(file));
  const concurrency = Math.max(1, dependencies.concurrency ?? DEFAULT_CONCURRENCY);
  const journal = dependencies.journal ?? {
    read: () => null,
    write: () => undefined,
    clear: () => undefined,
  };
  const requestKey = dependencies.requestKey ?? newRequestKey;

  let paused = false;
  let active: UploadSession | null = null;

  async function authoritativeParts(uploadId: string): Promise<Set<number>> {
    const uploaded = new Set<number>();
    let marker: number | undefined;
    do {
      const page = await dependencies.listUploadParts(uploadId, marker);
      page.items.forEach((part) => uploaded.add(part.partNumber));
      marker = page.nextMarker ?? undefined;
    } while (marker != null);
    return uploaded;
  }

  function sliceFor(file: File, session: UploadSession, partNumber: number): Blob {
    const start = (partNumber - 1) * session.partSizeBytes;
    const end = Math.min(file.size, start + session.partSizeBytes);
    return file.slice(start, end);
  }

  async function uploadOne(file: File, session: UploadSession, partNumber: number): Promise<void> {
    if (paused) throw new UploadPausedError();
    let lastError: unknown = null;
    for (let attempt = 1; attempt <= MAX_PART_ATTEMPTS; attempt += 1) {
      if (paused) throw new UploadPausedError();
      const signed = (await dependencies.signUploadParts(session.id, [partNumber]))[0];
      try {
        await uploadPart(signed, sliceFor(file, session, partNumber));
        return;
      } catch (error) {
        lastError = error;
        const refreshable = error instanceof StorageUploadError
          && (error.status === 400 || error.status === 403);
        if (!refreshable || attempt === MAX_PART_ATTEMPTS) throw error;
      }
    }
    throw lastError;
  }

  async function uploadMissing(
    file: File,
    session: UploadSession,
    missing: number[],
  ): Promise<void> {
    const queue = [...missing];
    const failures: unknown[] = [];
    const workers = Array.from({ length: Math.min(concurrency, queue.length) }, async () => {
      while (queue.length > 0) {
        if (paused || failures.length > 0) return;
        const partNumber = queue.shift() as number;
        try {
          await uploadOne(file, session, partNumber);
        } catch (error) {
          failures.push(error);
        }
      }
    });
    await Promise.all(workers);
    if (failures.length > 0) throw failures[0];
    if (paused) throw new UploadPausedError();
  }

  async function complete(uploadId: string): Promise<UploadSession> {
    const key = requestKey();
    let lastError: unknown = null;
    for (let attempt = 1; attempt <= MAX_COMPLETION_ATTEMPTS; attempt += 1) {
      try {
        const session = await dependencies.completeUpload(uploadId, key);
        if (session.state === 'COMPLETED' && session.jobId) return session;
        lastError = new Error('The upload is still finalizing.');
      } catch (error) {
        if (!isRetryableCompletion(error)) throw error;
        lastError = error;
      }
    }
    throw lastError;
  }

  async function run(
    file: File,
    session: UploadSession,
    fingerprint: string,
  ): Promise<UploadSession> {
    active = session;
    paused = false;
    journal.write({
      uploadId: session.id,
      movieId: session.movieId,
      fingerprint,
      fileName: file.name,
      sizeBytes: file.size,
    });
    const uploaded = await authoritativeParts(session.id);
    const missing: number[] = [];
    for (let partNumber = 1; partNumber <= session.totalParts; partNumber += 1) {
      if (!uploaded.has(partNumber)) missing.push(partNumber);
    }
    if (missing.length > 0) dependencies.onPhase?.('uploading');
    await uploadMissing(file, session, missing);
    // Completion is reported separately so the UI keeps saying "Finalizing" until the server
    // answers COMPLETED with a durable job id, never just because the bytes are sent.
    dependencies.onPhase?.('finalizing');
    const completed = await complete(session.id);
    journal.clear();
    active = completed;
    return completed;
  }

  return {
    fingerprint: fingerprintOf,
    get session() {
      return active;
    },
    async start(file, session) {
      const fingerprint = await fingerprintOf(file);
      return run(file, session, fingerprint);
    },
    async resume(file, session, expectedFingerprint) {
      const fingerprint = await fingerprintOf(file);
      const expected = expectedFingerprint ?? journal.read()?.fingerprint;
      if (expected && expected !== fingerprint) {
        throw new FingerprintMismatchError();
      }
      active = session;
      return run(file, session, fingerprint);
    },
    pause() {
      paused = true;
    },
    async abort() {
      if (!active) throw new Error('There is no active upload to abort');
      if (active.state !== 'OPEN') return active;
      const aborted = await dependencies.abortUpload(active.id);
      journal.clear();
      active = aborted;
      return aborted;
    },
  };
}
