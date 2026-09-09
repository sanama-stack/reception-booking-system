/**
 * The wire shapes of `/public/*`, mirroring `PublicResponses` and `PublicRequests` on the server
 * (docs/04-api-overview.md §6).
 *
 * **These are deliberately not the dashboard's types.** The server hand-writes every public
 * response so that a field is published because somebody typed it into `PublicResponses`, and
 * mirroring that with a hand-written type on this side keeps the two halves of the contract the
 * same size. Reusing `business.BusinessProfile` here would be the more convenient lie: it carries
 * an id, a slug, the cost cap and the booking horizon, none of which a stranger is ever sent, and
 * the first screen to read one of them would compile.
 *
 * `Money`, `DayOfWeek` and `WallClockTime` *are* reused, because those are the same shape on both
 * surfaces and a second definition of `{ amount, currency }` is a second place for the rule that
 * money is never a float to be forgotten.
 */

import type { DayOfWeek, WallClockTime } from '@/lib/business';
import type { Money } from '@/lib/catalog';
import type { IsoDate, IsoInstant, Timezone } from '@/lib/time';

/** One day's opening hours. A day absent from the list is a day the business is closed. */
export interface PublicDayHours {
  dayOfWeek: DayOfWeek;
  opensAt: WallClockTime;
  closesAt: WallClockTime;
}

/**
 * The booking page's header — who this business is, when they are open, and what they will do if
 * you cancel late.
 *
 * No id and no slug. The slug in the URL is the only handle a public caller needs, and the
 * Business's primary key is never one of the things a stranger is given.
 */
export interface PublicBusiness {
  name: string;
  description: string | null;
  addressLine: string | null;
  city: string | null;
  country: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  timezone: Timezone;
  currency: string;
  cancellationWindowHours: number;
  cancellationPolicy: string | null;
  /** Whether this business runs the Receptionist. Phase 09 reads it to decide on the chat panel. */
  aiEnabled: boolean;
  hours: PublicDayHours[];
}

/**
 * A bookable Service.
 *
 * The Buffers are absent, and that is the server's decision rather than an omission here: they are
 * how the business runs its day, not part of what the Customer is buying, and publishing them
 * would let anyone reconstruct the real occupancy of the calendar from the availability grid.
 */
export interface PublicService {
  id: string;
  name: string;
  description: string | null;
  durationMinutes: number;
  price: Money;
}

/** Names and job titles only (docs/06-security.md §5). No email, no phone, no user link. */
export interface PublicEmployee {
  id: string;
  fullName: string;
  jobTitle: string | null;
}

/** What a Service is called, on an Appointment that has already been booked. */
export interface BookedService {
  name: string;
  durationMinutes: number;
}

/** Who is performing it. A name — the Customer is meeting a person, not an id. */
export interface BookedEmployee {
  fullName: string;
}

/**
 * The confirmation screen.
 *
 * The Confirmation Code is here because this is the one moment it can be shown: the email is on
 * its way but has not arrived, and a Customer who closes this tab without it has to wait.
 */
export interface BookedAppointment {
  id: string;
  confirmationCode: string;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  timezone: Timezone;
  service: BookedService;
  employee: BookedEmployee;
  price: Money;
}

/**
 * Who is booking. No password and no account — a Customer is a name and a reachable number.
 *
 * **Nested, unlike the dashboard's flat body.** That is the published contract
 * (docs/04-api-overview.md §6), and it is why the server reports failures on this request under
 * dotted paths: a client looks each `errors[].field` up by exact name, so a message about
 * `customer.phone` reported as `phone` would match no input on the page and vanish.
 */
export interface CustomerDetails {
  fullName: string;
  phone: string;
  email?: string;
}

/**
 * Booking from the public page.
 *
 * No `businessId`, no `price`, no `source` and no `status`: the tenant comes from the slug, the
 * price is snapshotted from the Service, the source is set by the controller, and a Customer does
 * not get to choose what state their Appointment is in.
 */
export interface CreatePublicAppointment {
  serviceId: string;
  /**
   * Required even for "Anyone available". The Slot the Customer clicked already named the person
   * who would perform it, and resolving one again here would be a second chance to pick somebody
   * other than the one on the screen they agreed to.
   */
  employeeId: string;
  startsAt: IsoInstant;
  customer: CustomerDetails;
  note?: string;
}

/** The question the availability grid asks. `to` is inclusive; the server refuses over 31 days. */
export interface PublicAvailabilityQuery {
  serviceId: string;
  from: IsoDate;
  to: IsoDate;
  employeeId?: string;
}
