import { Spinner } from '@/components/ui';

/**
 * What a screen shows while it does not yet know who is signed in.
 *
 * Both guards render this, deliberately: AuthGuard and GuestGuard are mirror images, and a
 * visitor being handed between them should not see the pending state change appearance depending
 * on which one is deciding. It is a full-height centred spinner because both guards occupy the
 * whole screen — the alternative, rendering the screen's real content and correcting it a moment
 * later, is the flash of wrong state these guards exist to prevent.
 */
export function SessionPending({ label = 'Checking your session' }: { label?: string }) {
  return (
    <div className="flex min-h-dvh items-center justify-center" aria-busy="true">
      <Spinner className="text-ink-muted size-6" />
      <span className="sr-only">{label}</span>
    </div>
  );
}
