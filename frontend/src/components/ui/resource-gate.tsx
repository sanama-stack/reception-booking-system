'use client';

import type { Resource } from '@/lib/api/use-resource';
import { ErrorState } from './error-state';
import { Spinner } from './spinner';

/**
 * The three states every screen owes its user, in one place
 * (docs/09-phase-plan.md §5, rule 7).
 *
 * Loading and error are the same on every screen, so they live here; *empty* is not, because an
 * empty closure list and an empty FAQ list say different things and offer different next steps.
 * That one stays with the screen, inside `children`.
 *
 * A reload keeps the current content on screen rather than replacing it with the spinner —
 * `loading` is true only for the first load — so saving does not make the page flicker.
 */
export function ResourceGate<T>({
  resource,
  children,
}: {
  resource: Resource<T>;
  children: (data: T) => React.ReactNode;
}) {
  if (resource.data !== null) return <>{children(resource.data)}</>;

  if (resource.loading) {
    return (
      <div className="flex justify-center py-16" aria-busy="true">
        <Spinner className="text-ink-muted size-6" />
        <span className="sr-only">Loading</span>
      </div>
    );
  }

  if (resource.error) {
    return <ErrorState error={resource.error} onRetry={() => void resource.reload()} />;
  }

  return null;
}
