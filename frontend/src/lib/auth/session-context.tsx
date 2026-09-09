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

  // A session the client cannot recover — a refresh that failed, or a 401 saying there was nothing
  // to refresh with — is over, and flipping the status here is what sends the user to sign in:
  // AuthGuard watches it. Registered in one place, so a burst of failing requests produces one
  // redirect rather than one per request.
  //
  // **Only while there is a session to lose.** `client.ts` cannot tell a lapsed session from a
  // visitor who never had one — httpOnly cookies mean it cannot see what it is sending, and both
  // are answered `401 UNAUTHENTICATED`. This provider can, and the distinction is load-bearing
  // because it sits in the root layout: every public page runs the `/auth/me` above, and a signed
  // out visitor's landing page would otherwise report a session expiring on every single load.
  useEffect(() => {
    if (status !== 'authenticated') return;
    setSessionExpiredHandler(() => {
      setSession(null);
      setStatus('anonymous');
    });
    return () => setSessionExpiredHandler(null);
  }, [status]);

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
