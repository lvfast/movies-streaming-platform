import { useState } from 'react';
import type { AdminMovie } from '../../api/admin-api';

export type PublicationCommand = 'publish' | 'activate' | 'unpublish' | 'archive' | 'restore';

export interface PublicationActionsProps {
  movie: AdminMovie;
  hasCandidate: boolean;
  /** True only when the selected version is the replacement candidate, never the active version. */
  canActivate: boolean;
  publishable: boolean;
  busy?: boolean;
  onCommand: (command: PublicationCommand) => void;
}

const LABELS: Record<PublicationCommand, string> = {
  publish: 'Publish',
  activate: 'Activate replacement',
  unpublish: 'Unpublish',
  archive: 'Archive',
  restore: 'Restore',
};

/**
 * Explicit, confirmed lifecycle controls. No permanent delete action exists anywhere in the
 * workspace. Every command requires a second, visible confirmation before it is dispatched.
 */
export function PublicationActions({
  movie, hasCandidate, canActivate, publishable, busy = false, onCommand,
}: PublicationActionsProps) {
  const [pending, setPending] = useState<PublicationCommand | null>(null);

  const commands: PublicationCommand[] = [];
  if (movie.lifecycle === 'DRAFT') commands.push('publish', 'archive');
  if (movie.lifecycle === 'PUBLISHED') commands.push('unpublish', 'archive');
  if (movie.lifecycle === 'UNPUBLISHED') commands.push('publish', 'archive');
  if (movie.lifecycle === 'ARCHIVED') commands.push('restore');
  if (hasCandidate && movie.lifecycle === 'PUBLISHED') commands.push('activate');

  return (
    <section className="admin-publication" aria-label="Publication actions">
      <div className="admin-publication__buttons">
        {commands.map((command) => (
          <button
            key={command}
            className={command === 'publish' || command === 'activate' ? 'button button--primary' : 'button button--ghost'}
            type="button"
            disabled={busy
              || ((command === 'publish' || command === 'activate') && !publishable)
              || (command === 'activate' && !canActivate)}
            onClick={() => setPending(command)}
          >
            {LABELS[command]}
          </button>
        ))}
      </div>

      {pending ? (
        <div className="admin-confirm" role="group" aria-label={`Confirm ${LABELS[pending].toLowerCase()}`}>
          <p>
            {pending === 'restore'
              ? 'Restore this film to UNPUBLISHED? It is not republished automatically.'
              : `Are you sure you want to ${LABELS[pending].toLowerCase()} this film?`}
          </p>
          <div className="admin-confirm__actions">
            <button
              className="button button--primary"
              type="button"
              disabled={busy}
              onClick={() => {
                const command = pending;
                setPending(null);
                onCommand(command);
              }}
            >
              Confirm {LABELS[pending].toLowerCase()}
            </button>
            <button className="button button--ghost" type="button" onClick={() => setPending(null)}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}
    </section>
  );
}
