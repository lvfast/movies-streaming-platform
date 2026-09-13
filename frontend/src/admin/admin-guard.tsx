import type { ReactNode } from 'react';
import { LoadingState } from '../components/feedback';
import { useSession } from '../session/session-context';

export function AdminGuard({ children }: { children: ReactNode }) {
  const { status, isAdmin, openAuth } = useSession();

  if (status === 'booting') {
    return (
      <main className="admin-page admin-page--center">
        <LoadingState label="Checking access" />
      </main>
    );
  }

  if (status === 'guest') {
    return (
      <main className="admin-page admin-page--center">
        <section className="admin-access-denied" role="alert">
          <h1>Sign in required</h1>
          <p>Administrators must sign in before opening the dashboard.</p>
          <button className="button button--primary" type="button" onClick={() => openAuth('login')}>
            Sign in
          </button>
        </section>
      </main>
    );
  }

  if (!isAdmin) {
    return (
      <main className="admin-page admin-page--center">
        <section className="admin-access-denied" role="alert">
          <h1>You don&apos;t have access</h1>
          <p>This workspace is limited to administrators.</p>
        </section>
      </main>
    );
  }

  return <>{children}</>;
}
