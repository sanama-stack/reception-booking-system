'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { ErrorState } from '@/components/ui';
import { SessionPending, useSession } from '@/lib/auth';

/**
 * Keeps signed-out visitors out of the dashboard.
 *
 * This is a redirect, not a security control. The session lives in an httpOnly cookie the server
 * validates on every request, so what actually protects a business's data is that the API refuses
 * — this only spares the user a screen full of failed requests. A guard that were the only check
 * would be no check at all. GuestGuard, in the (auth) group, is its mirror.
 */
export function AuthGuard({ children }: { children: React.ReactNode }) {
  const { status, error } = useSession();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (status === 'anonymous') {
      // Where they were going, so signing in returns them there rather than to a generic home.
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [status, router, pathname]);

  if (status === 'loading') {
    return <SessionPending />;
  }

  if (status === 'anonymous') {
    return (
      <div className="mx-auto flex min-h-dvh max-w-md items-center px-6">
        {/* Only shown if /auth/me failed for a reason other than being signed out — a server
            that is down should say so rather than bouncing the user to a login that will also
            fail. */}
        {error ? <ErrorState error={error} /> : null}
      </div>
    );
  }

  return <>{children}</>;
}
