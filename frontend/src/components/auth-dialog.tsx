import { X } from 'lucide-react';
import { type FormEvent, useEffect, useState } from 'react';
import { ProblemError } from '../api/streaming-api';
import { useSession } from '../session/session-context';

export function AuthDialog() {
  const {
    authDialogOpen,
    authMode,
    authenticate,
    closeAuth,
    openAuth,
  } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!authDialogOpen) return;
    setError(null);
    setPassword('');
  }, [authDialogOpen, authMode]);

  useEffect(() => {
    if (!authDialogOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeAuth();
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [authDialogOpen, closeAuth]);

  if (!authDialogOpen) return null;

  const isRegister = authMode === 'register';

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await authenticate(authMode, { username, password });
    } catch (caught) {
      setError(caught);
    } finally {
      setSubmitting(false);
    }
  }

  const problem = error instanceof ProblemError ? error.problem : null;

  return (
    <div className="dialog-backdrop" role="presentation" onMouseDown={closeAuth}>
      <section
        aria-labelledby="auth-title"
        aria-modal="true"
        className="auth-dialog"
        onMouseDown={(event) => event.stopPropagation()}
        role="dialog"
      >
        <button className="icon-button auth-dialog__close" type="button" onClick={closeAuth} aria-label="Close">
          <X aria-hidden="true" />
        </button>
        <p className="eyebrow">LVFAST Cinema</p>
        <h2 id="auth-title">{isRegister ? 'Create your demo account' : 'Welcome back'}</h2>
        <p className="auth-dialog__note">
          This is a public demo. Account and viewing data may be reset. Use a unique password and avoid personal information.
        </p>
        <form onSubmit={submit}>
          <label htmlFor="auth-username">Username</label>
          <input
            autoFocus
            autoComplete="username"
            id="auth-username"
            minLength={3}
            maxLength={32}
            pattern="[A-Za-z0-9_]+"
            required
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
          <label htmlFor="auth-password">Password</label>
          <input
            autoComplete={isRegister ? 'new-password' : 'current-password'}
            id="auth-password"
            minLength={12}
            maxLength={128}
            required
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          {problem ? (
            <div className="form-error" role="alert">
              <strong>{problem.detail}</strong>
              {problem.fieldErrors?.map((fieldError) => (
                <span key={`${fieldError.field}-${fieldError.code}`}>{fieldError.message}</span>
              ))}
              <small>Reference: {problem.requestId}</small>
            </div>
          ) : error ? (
            <div className="form-error" role="alert">
              We could not complete that request. Please try again.
            </div>
          ) : null}
          <button className="button button--primary auth-dialog__submit" disabled={submitting} type="submit">
            {submitting ? 'Please wait…' : isRegister ? 'Create account' : 'Sign in'}
          </button>
        </form>
        <p className="auth-dialog__switch">
          {isRegister ? 'Already have an account?' : 'New to LVFAST Cinema?'}{' '}
          <button type="button" onClick={() => openAuth(isRegister ? 'login' : 'register')}>
            {isRegister ? 'Sign in' : 'Create an account'}
          </button>
        </p>
      </section>
    </div>
  );
}
