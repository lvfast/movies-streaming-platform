import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Play, RefreshCw } from 'lucide-react';
import { useAdminApi } from '../../api/api-context';
import {
  RevisionConflictError,
  type AdminMovie,
  type AssetPreview,
  type ManagedPlayback,
  type MediaAsset,
  type MediaVersion,
} from '../../api/admin-api';
import { ProblemError } from '../../api/streaming-api';
import { ErrorState, LoadingState } from '../../components/feedback';
import { PreviewPlayer } from './preview-player';
import { PublicationActions, type PublicationCommand } from './publication-actions';

function newRequestKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `cmd-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

function messageOf(error: unknown): string {
  if (error instanceof ProblemError) return error.problem.detail;
  return 'The command could not be completed. Please try again.';
}

function isRetryable(error: unknown): boolean {
  if (error instanceof RevisionConflictError) return false;
  const status = (error as { problem?: { status?: number } })?.problem?.status;
  return status == null || status === 0 || status >= 500;
}

export function MovieReviewPage() {
  const { movieId = '' } = useParams();
  const api = useAdminApi();

  const [movie, setMovie] = useState<AdminMovie | null>(null);
  const [revision, setRevision] = useState(0);
  const [versions, setVersions] = useState<MediaVersion[]>([]);
  const [assets, setAssets] = useState<MediaAsset[]>([]);
  const [selectedVersionId, setSelectedVersionId] = useState<string | null>(null);
  const [posterAssetId, setPosterAssetId] = useState<string | null>(null);
  const [backdropAssetId, setBackdropAssetId] = useState<string | null>(null);
  const [preview, setPreview] = useState<ManagedPlayback | null>(null);
  const [artworkPreview, setArtworkPreview] = useState<AssetPreview | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async (signal?: AbortSignal) => {
    const [versioned, versionPage, assetPage] = await Promise.all([
      api.getMovie(movieId, signal),
      api.listVersions(movieId, { size: 100 }, signal),
      api.listAssets(movieId, { size: 100 }, signal),
    ]);
    setMovie(versioned.data);
    setRevision(versioned.revision);
    setVersions(versionPage.items);
    setAssets(assetPage.items);
    return versioned.data;
  }, [api, movieId]);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setLoading(true);
    setLoadError(null);
    reload(controller.signal)
      .then(() => active && setLoading(false))
      .catch((error) => {
        if (!active || isAbort(error)) return;
        setLoadError(error);
        setLoading(false);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [reload]);

  const readyVersions = useMemo(() => versions.filter((version) => version.state === 'READY'), [versions]);
  const activeVersionId = movie?.activeMediaVersionId ?? null;
  const candidate = useMemo(
    () => readyVersions.find((version) => version.id !== activeVersionId) ?? null,
    [readyVersions, activeVersionId],
  );
  const selectedVersion = useMemo(
    () => readyVersions.find((version) => version.id === selectedVersionId)
      ?? readyVersions.find((version) => version.id === activeVersionId)
      ?? candidate,
    [readyVersions, selectedVersionId, activeVersionId, candidate],
  );
  const readyPoster = useMemo(
    () => assets.find((asset) => asset.kind === 'POSTER' && asset.state === 'READY') ?? null,
    [assets],
  );
  const readyBackdrop = useMemo(
    () => assets.find((asset) => asset.kind === 'BACKDROP' && asset.state === 'READY') ?? null,
    [assets],
  );
  // The fallback keeps the publish payload complete when no artwork was explicitly attached, but
  // "Selected" and the disabled Attach action only reflect an explicit pointer: otherwise the
  // admin could never perform the attach action that records MOVIE_ARTWORK_ATTACHED.
  const effectivePosterId = posterAssetId ?? movie?.posterAssetId ?? readyPoster?.id ?? null;
  const effectiveBackdropId = backdropAssetId ?? movie?.backdropAssetId ?? readyBackdrop?.id ?? null;
  const selectedPosterId = posterAssetId ?? movie?.posterAssetId ?? null;
  const selectedBackdropId = backdropAssetId ?? movie?.backdropAssetId ?? null;
  const metadataComplete = Boolean(movie?.synopsis && movie.synopsis.trim().length > 0);
  const canPublish = metadataComplete && selectedVersion != null
    && effectivePosterId != null && effectiveBackdropId != null;
  // Activation only ever targets a replacement candidate, never the version that is already active.
  const canActivate = candidate != null && selectedVersion?.id === candidate.id;

  async function runCommand(command: PublicationCommand): Promise<void> {
    if (!movie || busy) return;
    if (command === 'activate' && selectedVersion?.id !== candidate?.id) {
      setActionError(new Error('Select the candidate version before activating it.'));
      return;
    }
    setBusy(true);
    setActionError(null);
    setNotice(null);
    const key = newRequestKey();
    const attempt = async () => {
      if (command === 'publish' && selectedVersion) {
        return api.publishMovie(
          movieId,
          {
            mediaVersionId: selectedVersion.id,
            posterAssetId: effectivePosterId,
            backdropAssetId: effectiveBackdropId,
          },
          revision,
          key,
        );
      }
      if (command === 'activate' && selectedVersion) {
        return api.activateVersion(
          movieId, { mediaVersionId: selectedVersion.id }, revision, key);
      }
      if (command === 'unpublish') return api.unpublishMovie(movieId, revision);
      if (command === 'archive') return api.archiveMovie(movieId, revision);
      return api.restoreMovie(movieId, revision);
    };
    try {
      try {
        await attempt();
      } catch (error) {
        if (!isRetryable(error)) throw error;
        await attempt();
      }
      await reload();
      setNotice('The film state was updated.');
    } catch (error) {
      setActionError(error);
      if (error instanceof RevisionConflictError) {
        await reload().catch(() => undefined);
      }
    } finally {
      setBusy(false);
    }
  }

  async function attach(kind: 'POSTER' | 'BACKDROP', assetId: string): Promise<void> {
    if (!movie || busy) return;
    setBusy(true);
    setActionError(null);
    try {
      const result = await api.attachArtwork(movieId, { kind, assetId }, revision);
      setMovie(result.data);
      setRevision(result.revision);
      if (kind === 'POSTER') setPosterAssetId(assetId);
      else setBackdropAssetId(assetId);
      setNotice(`The ${kind.toLowerCase()} artwork was attached.`);
    } catch (error) {
      setActionError(error);
    } finally {
      setBusy(false);
    }
  }

  async function startPreview(): Promise<void> {
    if (!movie || !selectedVersion) return;
    setActionError(null);
    try {
      const grant = await api.previewMovie(movieId, selectedVersion.id);
      setPreview(grant);
    } catch (error) {
      setActionError(error);
    }
  }

  const renewPreview = useCallback(async () => {
    if (!preview) throw new Error('No preview session');
    return api.previewToken(preview.sessionId);
  }, [api, preview]);

  if (loading) {
    return <main className="admin-page admin-page--center"><LoadingState label="Loading film" /></main>;
  }
  if (loadError || !movie) {
    return (
      <main className="admin-page admin-page--center">
        <ErrorState error={loadError} />
      </main>
    );
  }

  return (
    <main className="admin-page">
      <header className="admin-page__header">
        <h1>{movie.title}</h1>
        <span className={`admin-badge admin-badge--${movie.lifecycle.toLowerCase()}`}>{movie.lifecycle}</span>
      </header>

      {actionError ? <p className="admin-form__error" role="alert">{messageOf(actionError)}</p> : null}
      {notice ? <p className="admin-form__notice" role="status">{notice}</p> : null}

      <section className="admin-review" aria-label="Versions and artwork">
        <div className="admin-review__versions">
          <h2>Media versions</h2>
          {readyVersions.length === 0 ? (
            <p>No READY media versions yet.</p>
          ) : (
            <ul className="admin-review__list">
              {readyVersions.map((version) => (
                <li key={version.id}>
                  <label className="admin-version">
                    <input
                      type="radio"
                      name="media-version"
                      value={version.id}
                      checked={selectedVersion?.id === version.id}
                      onChange={() => setSelectedVersionId(version.id)}
                    />
                    <span className="admin-version__id">{version.id.slice(0, 8)}</span>
                    <span className="admin-badge">READY</span>
                    {version.id === activeVersionId ? <span className="admin-badge admin-badge--published">Active</span> : null}
                    {version.id === candidate?.id ? <span className="admin-badge admin-badge--draft">Candidate</span> : null}
                  </label>
                </li>
              ))}
            </ul>
          )}
          <button
            className="button button--ghost"
            type="button"
            disabled={!selectedVersion}
            onClick={() => void startPreview()}
          >
            <Play aria-hidden="true" size={16} />
            Preview selected
          </button>
        </div>

        <div className="admin-review__artwork">
          <h2>Artwork</h2>
          <ArtworkRow kind="POSTER" asset={readyPoster} selectedId={selectedPosterId} busy={busy} onSelect={attach} onPreview={setArtworkPreview} />
          <ArtworkRow kind="BACKDROP" asset={readyBackdrop} selectedId={selectedBackdropId} busy={busy} onSelect={attach} onPreview={setArtworkPreview} />
        </div>
      </section>

      <section className="admin-review__checklist" aria-label="Publish readiness">
        <h2>Before publishing</h2>
        <ul>
          <li data-ok={metadataComplete}>Synopsis is present</li>
          <li data-ok={selectedVersion != null}>A READY version is selected</li>
          <li data-ok={effectivePosterId != null}>A READY poster is selected</li>
          <li data-ok={effectiveBackdropId != null}>A READY backdrop is selected</li>
        </ul>
      </section>

      {preview ? (
        <div className="admin-review__preview">
          <h2>Preview</h2>
          <PreviewPlayer playback={preview} renew={renewPreview} />
        </div>
      ) : null}

      {artworkPreview ? (
        <div className="admin-review__artwork-preview">
          <img src={artworkPreview.url} alt="Selected artwork preview" />
        </div>
      ) : null}

      <div className="admin-review__actions">
        <PublicationActions
          movie={movie}
          hasCandidate={candidate != null}
          canActivate={canActivate}
          publishable={canPublish}
          busy={busy}
          onCommand={(command) => void runCommand(command)}
        />
      </div>

      <nav className="admin-review__links">
        <Link to={`/admin/movies/${movie.id}`}>Edit metadata</Link>
        <Link to={`/admin/movies/${movie.id}/uploads`}>Uploads</Link>
      </nav>
    </main>
  );
}

interface ArtworkRowProps {
  kind: 'POSTER' | 'BACKDROP';
  asset: MediaAsset | null;
  selectedId: string | null;
  busy: boolean;
  onSelect: (kind: 'POSTER' | 'BACKDROP', assetId: string) => void;
  onPreview: (preview: AssetPreview) => void;
}

function ArtworkRow({ kind, asset, selectedId, busy, onSelect, onPreview }: ArtworkRowProps) {
  const api = useAdminApi();
  const selected = asset != null && selectedId === asset.id;

  async function preview(): Promise<void> {
    if (!asset) return;
    try {
      onPreview(await api.previewAsset(asset.id));
    } catch {
      // A preview failure is non-fatal; the admin can retry.
    }
  }

  return (
    <div className="admin-artwork-row">
      <span className="admin-artwork-row__kind">{kind === 'POSTER' ? 'Poster' : 'Backdrop'}</span>
      {asset ? (
        <>
          <span className="admin-badge">READY</span>
          {selected ? <span className="admin-badge admin-badge--published">Selected</span> : null}
          <button className="button button--ghost" type="button" disabled={busy || selected} onClick={() => onSelect(kind, asset.id)}>
            Attach
          </button>
          <button className="button button--ghost" type="button" onClick={() => void preview()}>
            <RefreshCw aria-hidden="true" size={16} />
            Preview
          </button>
        </>
      ) : (
        <span className="admin-artwork-row__missing">No READY {kind.toLowerCase()} yet</span>
      )}
    </div>
  );
}
