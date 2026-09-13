import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useApi } from '../api/api-context';
import { subscribeAuthUser } from '../api/streaming-api';
import type { Credentials, User } from '../api/streaming-api';

type SessionStatus = 'booting' | 'guest' | 'authenticated';
type AuthMode = 'login' | 'register';

interface SessionContextValue {
  status: SessionStatus;
  user: User | null;
  isAdmin: boolean;
  authDialogOpen: boolean;
  authMode: AuthMode;
  openAuth(mode?: AuthMode): void;
  closeAuth(): void;
  authenticate(mode: AuthMode, credentials: Credentials): Promise<void>;
  signOut(): Promise<void>;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [status, setStatus] = useState<SessionStatus>('booting');
  const [user, setUser] = useState<User | null>(null);
  const [authDialogOpen, setAuthDialogOpen] = useState(false);
  const [authMode, setAuthMode] = useState<AuthMode>('login');

  useEffect(() => {
    let active = true;
    api.restoreSession()
      .then((tokens) => {
        if (!active) return;
        setUser(tokens?.user ?? null);
        setStatus(tokens ? 'authenticated' : 'guest');
      })
      .catch(() => {
        if (!active) return;
        setUser(null);
        setStatus('guest');
      });
    return () => {
      active = false;
    };
  }, [api]);

  useEffect(() => subscribeAuthUser((refreshedUser) => {
    setUser(refreshedUser);
    setStatus('authenticated');
  }), []);

  const openAuth = useCallback((mode: AuthMode = 'login') => {
    setAuthMode(mode);
    setAuthDialogOpen(true);
  }, []);

  const closeAuth = useCallback(() => setAuthDialogOpen(false), []);

  const authenticate = useCallback(async (mode: AuthMode, credentials: Credentials) => {
    const tokens = mode === 'register'
      ? await api.register(credentials)
      : await api.login(credentials);
    setUser(tokens.user);
    setStatus('authenticated');
    setAuthDialogOpen(false);
  }, [api]);

  const signOut = useCallback(async () => {
    try {
      await api.logout();
    } finally {
      setUser(null);
      setStatus('guest');
    }
  }, [api]);

  const value = useMemo<SessionContextValue>(() => ({
    status,
    user,
    isAdmin: user?.roles.includes('ADMIN') ?? false,
    authDialogOpen,
    authMode,
    openAuth,
    closeAuth,
    authenticate,
    signOut,
  }), [status, user, authDialogOpen, authMode, openAuth, closeAuth, authenticate, signOut]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const session = useContext(SessionContext);
  if (!session) throw new Error('useSession must be used inside SessionProvider');
  return session;
}
