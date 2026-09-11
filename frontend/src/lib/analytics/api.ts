/**
 * The `GET /analytics/summary` read, named once so no screen writes a path string.
 *
 * A read whose question changes while the screen is open has to be expressible as a string, because
 * that string is what a keyed component re-runs on — the split `lib/scheduling` established. The
 * summary has no writes.
 */

import type { IsoDate } from '@/lib/time';

/** Both dates inclusive. The server refuses a range over 366 days, or a backwards one, with `422`. */
export function analyticsSummaryPath(from: IsoDate, to: IsoDate): string {
  return `/analytics/summary?from=${from}&to=${to}`;
}
