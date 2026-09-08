/**
 * The wire shapes of `GET /availability`, mirroring `AvailabilityResponses` on the server
 * (docs/04-api-overview.md §5).
 *
 * Every instant here carries the business's own offset and the envelope names the zone, so nothing
 * on this side ever has to guess which clock a time is on — and nothing may consult the browser's
 * (ADR-0003).
 */

import type { IsoDate, IsoInstant, Timezone } from '@/lib/time';

/**
 * Why there are no slots, returned instead of a bare empty list.
 *
 * The order below is the order the engine tries them, and each one makes the next moot: a result
 * never carries two. `CLOSED` is decided against the *service* rather than the calendar — an hour
 * of opening time cannot hold a two-hour service, and calling that `FULLY_BOOKED` would send an
 * owner looking for a cancellation that would not help.
 */
export type EmptyReason = 'NO_ELIGIBLE_EMPLOYEE' | 'OUTSIDE_HORIZON' | 'CLOSED' | 'FULLY_BOOKED';

/** Name and id only — nothing here is unsafe to show a customer once phase 08 opens this shape. */
export interface SlotEmployee {
  id: string;
  fullName: string;
}

export interface AvailableSlot {
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  /**
   * Always present, even when the caller named no employee. Booking never re-resolves one, because
   * a second resolution is a second chance to answer differently from what was shown.
   */
  employee: SlotEmployee;
}

/** One calendar date in the business timezone, present even when it holds nothing. */
export interface AvailabilityDay {
  date: IsoDate;
  slots: AvailableSlot[];
}

export interface Availability {
  timezone: Timezone;
  days: AvailabilityDay[];
  /**
   * `null` whenever any day holds a slot, and always sent — a client must be able to tell "there is
   * no reason" from "this server did not answer".
   */
  emptyReason: EmptyReason | null;
}

/** The question. `to` is inclusive, and the server refuses a range longer than 31 days. */
export interface AvailabilityQuery {
  serviceId: string;
  from: IsoDate;
  to: IsoDate;
  employeeId?: string;
}
