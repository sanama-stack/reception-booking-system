'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { ApiError, api, setSessionExpiredHandler } from '@/lib/api/client';
import type { Session } from './types';

/**
 * Who is signed in, hydrated from `/auth/me`.
 *
 * There is no token here, and there must never be one. The cookies are httpOnly and the browser
 * sends them because everything is on one origin — so "am I signed in?" is a question only the
 * server can answer, and this asks it rather than inspecting client state that could be stale or
 * forged (docs/06-security.md §2).
 */
interface SessionState {
  session: Session | null;
  status: 'loading' | 'authenticated' | 'anonymous';
  error: ApiError | null;
  /** Adopts a session returned by register or login, so no second round trip is needed. */
  adopt: (session: Session) => void;
  signOut: () => Promise<void>;
  reload: () => Promise<void>;
}

const SessionContext = createContext<SessionState | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [session, setSession] = useState<Session | null>(null);
  const [status, setStatus] = useState<SessionState['status']>('loading');
  const [error, setError] = useState<ApiError | null>(null);

  const load = useCallback(async () => {
    try {
      const me = await api.get<Session>('/auth/me');
      setSession(me);
      setStatus('authenticated');
      setError(null);
    } catch (cause) {
      setSession(null);
      setStatus('anonymous');
      // An unauthenticated answer is the expected one for a signed-out visitor, not an error to
      // show them. Anything else is worth surfacing.
      const apiError = cause instanceof ApiError ? cause : null;
      setError(apiError && apiError.status !== 401 ? apiError : null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // A refresh that fails means the session is genuinely over — the client cannot recover it, so
  // the user is sent to sign in. Registered once, here, so a burst of failing requests produces
  // one redirect rather than one per request.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setSession(null);
      setStatus('anonymous');
    });
    return () => setSessionExpiredHandler(null);
  }, []);

  const adopt = useCallback((next: Session) => {
    setSession(next);
    setStatus('authenticated');
    setError(null);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await api.post('/auth/logout');
    } finally {
      // Even if the call failed, the local session is over as far as this tab is concerned.
      setSession(null);
      setStatus('anonymous');
      router.replace('/login');
    }
  }, [router]);

  const value = useMemo<SessionState>(
    () => ({ session, status, error, adopt, signOut, reload: load }),
    [session, status, error, adopt, signOut, load],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionState {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error('useSession must be used inside a SessionProvider');
  }
  return context;
}
