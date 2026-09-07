'use client';

import Link from 'next/link';
import { Button } from '@/components/ui';
import { DEFAULT_SIGNED_IN_PATH, useSession } from '@/lib/auth';

/**
 * The landing page's way into the product, which depends on whether there is a session.
 *
 * The page around this is server-rendered and static, but "am I signed in?" is a question only the
 * server can answer about an httpOnly cookie, so the answer arrives after hydration
 * (lib/auth/session-context.tsx). That is why only this row is a client component: the hero stays
 * in the static payload, and the one part that cannot be known at build time is the one part that
 * waits.
 *
 * While it waits it renders a placeholder of the same height rather than the signed-out buttons.
 * Guessing "anonymous" is right most of the time on a public page and wrong in the way that reads
 * as a bug: a signed-in owner would see "Sign in", reach for it, and have it change under the
 * cursor. Same reasoning as AuthGuard's spinner — show that the answer is coming, not a wrong one.
 */
export function LandingCta() {
  const { session, status } = useSession();

  if (status === 'loading') {
    return (
      <div className="flex flex-wrap gap-3" aria-busy="true">
        <div className="bg-border h-10 w-40 animate-pulse rounded-md" />
        <span className="sr-only">Checking whether you are signed in</span>
      </div>
    );
  }

  if (status === 'authenticated' && session) {
    return (
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Link href={DEFAULT_SIGNED_IN_PATH}>
          <Button>Go to dashboard</Button>
        </Link>
        <p className="text-ink-muted text-sm">
          Signed in as {session.user.email} · {session.business.name}
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-wrap gap-3">
      <Link href="/register">
        <Button>Create your business</Button>
      </Link>
      <Link href="/login">
        <Button variant="secondary">Sign in</Button>
      </Link>
    </div>
  );
}
