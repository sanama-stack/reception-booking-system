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
 * `Money`, `DayOfWeek`, `WallClockTime` and `AppointmentStatus` *are* reused, because those are the
 * same shape on both surfaces and a second definition of `{ amount, currency }` is a second place
 * for the rule that money is never a float to be forgotten. The test is whether the thing genuinely
 * is the same — a closed domain enum, a value object — not whether the fields happen to overlap.
 */

import type { AppointmentStatus } from '@/lib/appointments';
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

/**
 * Who to contact when this page has to refuse.
 *
 * The policy text and a phone number are the whole point of this object, and it mirrors the
 * server's `ManagingBusiness` exactly: a refusal that says "contact the business" without saying
 * how is a dead end, which is the one thing the phase document names as a defect here.
 *
 * **It carries no `cancellationWindowHours`**, unlike `PublicBusiness`, so this page cannot say
 * *how long* before the start the deadline was — only whether it has passed, via `canCancel`. That
 * is a deliberate consequence of the response being minimised to what the refusal needs, and it is
 * why the copy on a closed window says the time has passed rather than naming a number it would
 * have to have guessed.
 */
export interface ManagingBusiness {
  name: string;
  phone: string | null;
  email: string | null;
  timezone: Timezone;
  cancellationPolicy: string | null;
}

/**
 * One appointment, as the person who booked it is allowed to see it.
 *
 * **No Customer record.** The person reading this is the Customer, and echoing back the stored
 * name, phone and email would turn a Confirmation Code into a way to read them.
 *
 * `AppointmentStatus` is reused from `lib/appointments` rather than redeclared. It is the same
 * closed domain enum on both surfaces — the server sends the identical four constants here and to
 * the dashboard — so a second union would be a second place for a fifth status to be forgotten.
 * That is the same test `Money` and `WallClockTime` pass at the top of this file, and it is not
 * the test `BusinessProfile` fails: what is reused is a shape that genuinely is the same, not a
 * DTO that happens to overlap.
 */
export interface ManagedAppointment {
  id: string;
  confirmationCode: string;
  status: AppointmentStatus;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  timezone: Timezone;
  service: BookedService;
  /**
   * A name, and **no id** — which decides how the reschedule grid is asked for.
   *
   * There is nothing here to pin the current Employee with, so `GET …/manage/availability` is
   * always asked without an `employeeId` and every reschedule grid is an "anyone eligible" grid.
   * That is the minimisation working as intended rather than a gap: the move can be completed
   * without the id, so by the rule this whole module is built on the id is not sent. The grid
   * labels each Slot with whoever would perform it, so the Customer is never moved to a different
   * person without being told.
   */
  employee: BookedEmployee;
  price: Money;
  note: string | null;
  /**
   * Whether the Cancellation Window is still open **and** the appointment is in a state that can
   * be cancelled — the two facts already combined, so this page never has to do that arithmetic
   * against a `cancellationWindowHours` it is not sent.
   *
   * A hint, never a control. It is what lets the policy text sit beside a disabled button instead
   * of the Customer pressing it and being refused; the endpoint checks it again regardless.
   */
  canCancel: boolean;
  canReschedule: boolean;
  business: ManagingBusiness;
}

/**
 * Proof that an appointment is yours: a Manage Link token, or the Confirmation Code and the phone
 * number that booked.
 *
 * Exactly one shape is presented, and the server decides which rather than the schema — "one of
 * these two" is not expressible as a field annotation. This page only ever sends the token, which
 * is the whole reason it exists; the other shape is typed because it is half of the published
 * contract and the first screen to need it should not have to rediscover the field names.
 */
export interface ManageAuthority {
  manageToken?: string;
  confirmationCode?: string;
  phone?: string;
}

/** A Customer cancelling. The reason is optional and goes into the audit trail. */
export interface CancelPublicAppointment {
  authority: ManageAuthority;
  reason?: string;
}

/**
 * A Customer moving one.
 *
 * `employeeId` is optional on the wire and absent means "keep the current person". It is sent
 * anyway, off the Slot that was shown, for the reason the booking body sends one: the grid already
 * named who would perform each time, and letting the server resolve one again would be a second
 * chance to answer differently from the button the Customer pressed.
 */
export interface ReschedulePublicAppointment {
  authority: ManageAuthority;
  startsAt: IsoInstant;
  employeeId?: string;
}

/**
 * The question the reschedule grid asks.
 *
 * No `serviceId`: a Customer rescheduling is moving what they booked, not choosing again, so the
 * server takes the Service off the appointment the token resolves. No `employeeId` either, in
 * practice — see `ManagedAppointment.employee`.
 */
export interface ManageAvailabilityQuery {
  token: string;
  from: IsoDate;
  to: IsoDate;
  employeeId?: string;
}
