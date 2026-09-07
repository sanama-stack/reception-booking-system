'use client';

import { Suspense, useEffect } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { SessionPending, safeNextPath, useSession } from '@/lib/auth';

/**
 * Keeps signed-in visitors off the sign-in and registration screens — the mirror of the
 * dashboard's AuthGuard, and the reason it lives in the layout rather than in either form: an
 * auth screen added later (password reset, an invitation acceptance) is governed the day it is
 * added, without anyone remembering to wire it up.
 *
 * **Why this reads the session rather than the cookie.** The obvious cheaper implementation is
 * middleware testing whether the session cookie is present, which would decide before the page
 * ever renders. It is also the one that breaks: cookie presence is not a valid session, so a
 * stale cookie would send the visitor to the dashboard, whose AuthGuard would find them
 * unauthenticated and send them back here — and back again, forever. Both guards read `status`
 * from the one SessionProvider, so within a page load they cannot disagree, and the loop is not
 * expressible. Do not move this to the edge.
 */
function GuestGuardInner({ children }: { children: React.ReactNode }) {
  const { status } = useSession();
  const router = useRouter();
  // Honoured on arrival too, not just after signing in: following a link to a dashboard screen
  // while already signed in should land on that screen, even though the link routed through here.
  const next = safeNextPath(useSearchParams().get('next'));

  useEffect(() => {
    if (status === 'authenticated') {
      router.replace(next);
    }
  }, [status, router, next]);

  // 'authenticated' holds the pending screen too — the redirect above is in flight, and a sign-in
  // form rendered underneath a navigation is the flash this exists to remove.
  if (status !== 'anonymous') {
    return <SessionPending />;
  }

  return <>{children}</>;
}

/**
 * `useSearchParams` makes a statically rendered page fail `next build` unless a Suspense boundary
 * sits above it (phase 02 handoff §6.7). The boundary is here rather than in the layout so the
 * requirement travels with the component that creates it.
 */
export function GuestGuard({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<SessionPending />}>
      <GuestGuardInner>{children}</GuestGuardInner>
    </Suspense>
  );
}
