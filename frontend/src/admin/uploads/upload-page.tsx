import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Pause, Play, Upload, X } from 'lucide-react';
import { useAdminApi } from '../../api/api-context';
import type { UploadSession } from '../../api/admin-api';
import { ProblemError } from '../../api/streaming-api';
import {
  browserUploadJournal,
  createUploadManager,
  FingerprintMismatchError,
  UploadPausedError,
  type UploadJournalEntry,
  type UploadManager,
} from './upload-manager';
import { uploadPartToSignedUrl } from './r2-part-uploader';
import { computeResumeFingerprint } from './upload-fingerprint';

type UploadKind = 'VIDEO' | 'POSTER' | 'BACKDROP';
type Phase = 'idle' | 'preparing' | 'uploading' | 'finalizing' | 'paused' | 'completed' | 'failed';

const KINDS: UploadKind[] = ['VIDEO', 'POSTER', 'BACKDROP'];

function newRequestKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `upload-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function contentTypeFor(kind: UploadKind, file: File): string {
  if (file.type) return file.type;
  if (kind === 'VIDEO') return 'video/mp4';
  return kind === 'POSTER' ? 'image/jpeg' : 'image/jpeg';
}

function messageOf(error: unknown): string {
  if (error instanceof ProblemError) return error.problem.detail;
  if (error instanceof FingerprintMismatchError) return error.message;
  return 'The upload could not be completed. Please try again.';
}

export function UploadPage() {
  const { movieId = '' } = useParams();
  const api = useAdminApi();
  const journal = useMemo(() => browserUploadJournal(), []);

  const [kind, setKind] = useState<UploadKind>('VIDEO');
  const [file, setFile] = useState<File | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [session, setSession] = useState<UploadSession | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const managerRef = useRef<UploadManager | null>(null);
  const [resumable, setResumable] = useState<UploadJournalEntry | null>(null);

  /** A journal entry only belongs to the movie whose upload page is open. */
  const readResumable = useCallback(() => {
    const entry = journal.read();
    return entry && entry.movieId === movieId ? entry : null;
  }, [journal, movieId]);

  useEffect(() => {
    setResumable(readResumable());
  }, [readResumable]);

  const makeManager = useCallback(() => createUploadManager({
    listUploadParts: api.listUploadParts,
    signUploadParts: api.signUploadParts,
    completeUpload: api.completeUpload,
    abortUpload: api.abortUpload,
    uploadPart: uploadPartToSignedUrl,
    journal,
    onPhase: (next) => setPhase(next),
  }), [api, journal]);

  async function start(): Promise<void> {
    if (!file || phase === 'preparing' || phase === 'uploading' || phase === 'finalizing') return;
    setPhase('preparing');
    setMessage(null);
    try {
      const fingerprint = await computeResumeFingerprint(file);
      const created = await api.createUpload(movieId, {
        kind,
        fileName: file.name,
        contentType: contentTypeFor(kind, file),
        sizeBytes: file.size,
        resumeFingerprint: fingerprint,
      }, newRequestKey());
      setSession(created);
      const manager = makeManager();
      managerRef.current = manager;
      const completed = await manager.start(file, created);
      setSession(completed);
      setPhase('completed');
      setResumable(null);
    } catch (error) {
      if (error instanceof UploadPausedError) {
        setPhase('paused');
        setResumable(readResumable());
        return;
      }
      setPhase('failed');
      setMessage(messageOf(error));
    }
  }

  async function resume(): Promise<void> {
    const entry = readResumable();
    if (!file || !entry) return;
    setPhase('preparing');
    setMessage(null);
    try {
      const existing = await api.getUpload(entry.uploadId);
      const manager = makeManager();
      managerRef.current = manager;
      const completed = await manager.resume(file, existing, entry.fingerprint);
      setSession(completed);
      setPhase('completed');
      setResumable(null);
    } catch (error) {
      if (error instanceof UploadPausedError) {
        setPhase('paused');
        return;
      }
      setPhase('failed');
      setMessage(messageOf(error));
    }
  }

  function pause(): void {
    managerRef.current?.pause();
  }

  async function abort(): Promise<void> {
    try {
      const aborted = await managerRef.current?.abort();
      if (aborted) setSession(aborted);
      setPhase('idle');
      setResumable(null);
    } catch (error) {
      setMessage(messageOf(error));
    }
  }

  return (
    <main className="admin-page">
      <header className="admin-page__header">
        <h1>Upload media</h1>
        <Link className="button button--ghost" to={`/admin/movies/${movieId}/review`}>Review film</Link>
      </header>

      <section className="admin-upload" aria-label="Upload">
        <label className="admin-field">
          <span>Media kind</span>
          <select
            aria-label="Media kind"
            value={kind}
            disabled={phase === 'uploading' || phase === 'finalizing'}
            onChange={(event) => setKind(event.target.value as UploadKind)}
          >
            {KINDS.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>

        <label className="admin-field">
          <span>File</span>
          <input
            aria-label="Media file"
            type="file"
            disabled={phase === 'uploading' || phase === 'finalizing'}
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </label>

        <div className="admin-upload__actions">
          <button className="button button--primary" type="button" disabled={!file || phase === 'uploading' || phase === 'finalizing'} onClick={() => void start()}>
            <Upload aria-hidden="true" size={16} />
            Start upload
          </button>
          {resumable && file ? (
            <button className="button button--ghost" type="button" onClick={() => void resume()}>
              <Play aria-hidden="true" size={16} />
              Resume {resumable.fileName}
            </button>
          ) : null}
          {phase === 'uploading' || phase === 'finalizing' ? (
            <button className="button button--ghost" type="button" onClick={pause}>
              <Pause aria-hidden="true" size={16} />
              Pause
            </button>
          ) : null}
          {phase === 'uploading' || phase === 'paused' ? (
            <button className="button button--ghost" type="button" onClick={() => void abort()}>
              <X aria-hidden="true" size={16} />
              Abort
            </button>
          ) : null}
        </div>

        <p className="admin-upload__status" role="status">
          {phase === 'preparing' ? 'Preparing…' : null}
          {phase === 'uploading' ? 'Uploading parts…' : null}
          {phase === 'finalizing' ? 'Finalizing…' : null}
          {phase === 'paused' ? 'Paused. Reselect the same file and resume.' : null}
          {phase === 'completed' && session?.jobId ? (
            <>Upload completed. <Link to={`/admin/jobs/${session.jobId}`}>Track the processing job</Link>.</>
          ) : null}
        </p>
        {message ? <p className="admin-form__error" role="alert">{message}</p> : null}
      </section>
    </main>
  );
}
