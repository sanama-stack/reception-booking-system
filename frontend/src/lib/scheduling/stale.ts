/**
 * Every refusal that means **the slot list on screen is out of date**.
 *
 * `SLOT_UNAVAILABLE` is the one the phase documents single out — the exclusion constraint refused
 * the write because somebody else took the time between the list being drawn and the button being
 * pressed (ADR-0002) — but it is not the only way the world moves underneath an open screen. A
 * service deactivated in another tab, an employee unassigned, or simply enough time passing for
 * the start to fall inside the minimum lead time all leave a list of times that can no longer be
 * booked.
 *
 * All of them get the same treatment, because the same thing is true of all of them: the answer on
 * screen was computed against a world that has changed, so it is re-asked and the selection is
 * dropped. Silently leaving the old times up — or leaving one selected — would invite the same
 * doomed button to be pressed again.
 *
 * A validation failure is deliberately **not** here. A mistyped phone number says nothing about
 * availability, and clearing a chosen time because a name was too long would be its own defect.
 *
 * **One list, for the dashboard and the public page.** Both book through the same
 * `BookingService`, so both can be refused for any of these reasons, and a second copy is a second
 * list for phase 09 to forget when its Tools introduce another code.
 */

import type { ErrorCode } from '@/lib/api/client';

const STALE_SLOT_CODES: ReadonlySet<ErrorCode> = new Set<ErrorCode>([
  'SLOT_UNAVAILABLE',
  'SERVICE_INACTIVE',
  'EMPLOYEE_INACTIVE',
  'EMPLOYEE_CANNOT_PERFORM_SERVICE',
  'OUTSIDE_BUSINESS_HOURS',
  'OUTSIDE_WORKING_HOURS',
  'BOOKING_IN_PAST',
  'BELOW_MIN_LEAD_TIME',
  'BEYOND_MAX_ADVANCE',
]);

/** Whether this refusal means the times on screen should be recomputed and the choice dropped. */
export function isStaleSlot(code: ErrorCode): boolean {
  return STALE_SLOT_CODES.has(code);
}
