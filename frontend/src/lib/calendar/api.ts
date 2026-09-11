/**
 * The `GET /calendar` read, named once so no screen writes a path string.
 *
 * A read whose question changes while the screen is open has to be expressible as a string, because
 * that string is what a keyed component re-runs on — the split `lib/scheduling` established and
 * `lib/appointments` follows. The calendar has no writes at all: clicking a block opens an
 * appointment, and clicking empty space goes to the booking flow.
 */

import type { IsoDate } from '@/lib/time';

/** Both dates inclusive. The server refuses a range over 35 days with a `422`. */
export function calendarPath(from: IsoDate, to: IsoDate): string {
  return `/calendar?from=${from}&to=${to}`;
}
