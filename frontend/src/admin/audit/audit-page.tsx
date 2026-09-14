import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAdminApi } from '../../api/api-context';
import type { AuditEvent, AuditEventPage } from '../../api/admin-api';
import { ErrorState } from '../../components/feedback';

const PAGE_SIZE = 20;

/** Action and entity values must match the strings the backend actually records. */
const ACTIONS = [
  'MOVIE_CREATED',
  'MOVIE_UPDATED',
  'MOVIE_ARTWORK_ATTACHED',
  'MOVIE_PUBLISHED',
  'MOVIE_VERSION_ACTIVATED',
  'MOVIE_UNPUBLISHED',
  'MOVIE_ARCHIVED',
  'MOVIE_RESTORED',
  'MOVIE_PREVIEWED',
  'JOB_RETRIED',
  'ROLE_GRANTED',
  'ROLE_REVOKED',
];

const ENTITY_TYPES = ['MOVIE', 'MEDIA_JOB', 'APP_USER'];

function displayValue(value: unknown): string {
  if (value == null) return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

function changedFields(event: AuditEvent): Array<{ key: string; before: unknown; after: unknown }> {
  const keys = new Set([...Object.keys(event.before ?? {}), ...Object.keys(event.after ?? {})]);
  const changes: Array<{ key: string; before: unknown; after: unknown }> = [];
  keys.forEach((key) => {
    const before = event.before?.[key];
    const after = event.after?.[key];
    if (JSON.stringify(before) !== JSON.stringify(after)) {
      changes.push({ key, before, after });
    }
  });
  return changes;
}

export function AuditPage() {
  const api = useAdminApi();
  const [searchParams, setSearchParams] = useSearchParams();

  const action = searchParams.get('action') ?? '';
  const entityType = searchParams.get('entityType') ?? '';
  const entityId = searchParams.get('entityId') ?? '';
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0);

  const [data, setData] = useState<AuditEventPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setData(null);
    setError(null);
    api.listAudit(
      { action: action || undefined, entityType: entityType || undefined, entityId: entityId || undefined, page, size: PAGE_SIZE },
      controller.signal,
    )
      .then((result) => {
        if (active) setData(result);
      })
      .catch((caught) => {
        if (active && !(caught instanceof DOMException && caught.name === 'AbortError')) setError(caught);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [api, action, entityType, entityId, page, reloadToken]);

  function updateParam(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== 'page') next.delete('page');
    setSearchParams(next);
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <main className="admin-page">
      <header className="admin-page__header">
        <h1>Audit history</h1>
      </header>

      <div className="admin-toolbar">
        <label className="admin-filter">
          <select aria-label="Filter by action" value={action} onChange={(event) => updateParam('action', event.target.value)}>
            <option value="">All actions</option>
            {ACTIONS.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        <label className="admin-filter">
          <select aria-label="Filter by entity type" value={entityType} onChange={(event) => updateParam('entityType', event.target.value)}>
            <option value="">All entities</option>
            {ENTITY_TYPES.map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        <label className="admin-filter">
          <input
            aria-label="Filter by entity id"
            placeholder="Entity id"
            value={entityId}
            onChange={(event) => updateParam('entityId', event.target.value)}
          />
        </label>
      </div>

      {error ? (
        <ErrorState error={error} onRetry={() => setReloadToken((token) => token + 1)} />
      ) : !data ? (
        <p role="status">Loading audit history…</p>
      ) : data.items.length === 0 ? (
        <section className="admin-empty"><p>No audit events match the current filters.</p></section>
      ) : (
        <>
          <ul className="admin-audit">
            {data.items.map((event) => (
              <li className="admin-audit__event" key={event.id}>
                <div className="admin-audit__summary">
                  <span className="admin-badge">{event.action}</span>
                  <span className="admin-audit__entity">{event.entityType}{event.entityId ? ` ${event.entityId.slice(0, 8)}` : ''}</span>
                  <span className="admin-audit__meta">{event.actorType} · {new Date(event.createdAt).toLocaleString()}</span>
                </div>
                <dl className="admin-audit__changes">
                  {changedFields(event).map((change) => (
                    <div key={change.key}>
                      <dt>{change.key}</dt>
                      <dd>
                        <span className="admin-audit__before">{displayValue(change.before)}</span>
                        <span aria-hidden="true"> → </span>
                        <span className="admin-audit__after">{displayValue(change.after)}</span>
                      </dd>
                    </div>
                  ))}
                </dl>
              </li>
            ))}
          </ul>
          <nav className="admin-pagination" aria-label="Pagination">
            <button className="button button--ghost" type="button" disabled={page === 0} onClick={() => updateParam('page', String(page - 1))}>
              Previous
            </button>
            <span>Page {page + 1} of {totalPages}</span>
            <button className="button button--ghost" type="button" disabled={page + 1 >= totalPages} onClick={() => updateParam('page', String(page + 1))}>
              Next
            </button>
          </nav>
        </>
      )}
    </main>
  );
}
