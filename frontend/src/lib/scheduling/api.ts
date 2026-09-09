/**
 * The `GET /availability` read.
 *
 * Nothing here takes a business id and nothing ever may (docs/04-api-overview.md §1) — the tenant
 * is the one on the session, and the same engine answers the public page and the Receptionist in
 * later phases.
 */

import type { AvailabilityQuery } from './types';

/**
 * The read expressed as a **path**, not as a call.
 *
 * `useResource` takes a path rather than a fetcher, deliberately, and this is the first read in the
 * app whose question changes while the screen is open. A path is what makes that expressible: a
 * different question is a different string, so the hook re-runs because the question moved and for
 * no other reason.
 */
export function availabilityPath(query: AvailabilityQuery): string {
  const params = new URLSearchParams({
    serviceId: query.serviceId,
    from: query.from,
    to: query.to,
  });
  if (query.employeeId) params.set('employeeId', query.employeeId);
  return `/availability?${params.toString()}`;
}
