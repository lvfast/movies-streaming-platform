import { useState } from 'react';

export function RevisionConflict({ onReload }: { onReload: () => void }) {
  const [confirming, setConfirming] = useState(false);

  return (
    <section className="admin-conflict" role="alert">
      <h2>This film was updated by another administrator</h2>
      <p>Your unsaved changes would overwrite their update.</p>
      {confirming ? (
        <div className="admin-conflict__confirm">
          <p>Discard your unsaved changes and load the latest version?</p>
          <div className="admin-conflict__actions">
            <button className="button button--danger" type="button" onClick={onReload}>
              Discard and reload
            </button>
            <button className="button button--ghost" type="button" onClick={() => setConfirming(false)}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="admin-conflict__actions">
          <button className="button button--primary" type="button" onClick={() => setConfirming(true)}>
            Reload latest
          </button>
        </div>
      )}
    </section>
  );
}
