/**
 * The wire shape of `GET /calendar`, mirroring `CalendarResponses` on the server.
 *
 * **Every instant here already carries the Business's offset and the envelope names the zone**, so
 * nothing on this side converts anything: the server resolved the zone precisely so the client
 * cannot get it wrong (`CalendarResponses`' own class comment). Read o'clock through
 * `lib/time`'s helpers with `range.timezone`, never from the browser's clock.
 *
 * A `CalendarAppointment` is deliberately **not** an `AppointmentDetail`. It carries the customer's
 * name and nothing else about them — no confirmation code, no phone, no email, no price — because
 * a coloured rectangle needs none of those and a week of them is not something to ship to a browser
 * to draw blocks. The drawer fetches the full row from `/appointments/{id}` when an appointment is
 * actually opened, and a server test asserts the phone number appears nowhere in this response.
 */

import type { AppointmentSource, AppointmentStatus, NamedRef } from '@/lib/appointments';
import type { IsoDate, IsoInstant, Timezone } from '@/lib/time';

export type { AppointmentSource, AppointmentStatus, NamedRef };

/** Both dates inclusive, and the zone every time in the body is rendered in. */
export interface CalendarRange {
  from: IsoDate;
  to: IsoDate;
  timezone: Timezone;
}

export interface CalendarAppointment {
  id: string;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  status: AppointmentStatus;
  service: NamedRef;
  employee: NamedRef;
  /** The name alone — see the module comment. */
  customerName: string;
  /** What booked it, which is what the AI badge is drawn from. */
  source: AppointmentSource;
}

/** A whole-business non-bookable span. No employee, because it covers everybody. */
export interface CalendarClosure {
  id: string;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  reason: string | null;
}

/** One Employee's non-bookable span, named so a column can say whose absence it is. */
export interface CalendarTimeOff {
  id: string;
  employee: NamedRef;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  reason: string | null;
}

/**
 * Everything a view draws, from one read.
 *
 * The three lists arrive together because they are one answer about one moment. Fetched
 * separately, a booking made between the first call and the last renders as a block with no
 * employee, or an employee with no block — and an empty Tuesday looks identical whether the
 * business was shut, the only eligible person was away, or nobody booked.
 */
export interface Calendar {
  range: CalendarRange;
  appointments: CalendarAppointment[];
  closures: CalendarClosure[];
  timeOff: CalendarTimeOff[];
}
