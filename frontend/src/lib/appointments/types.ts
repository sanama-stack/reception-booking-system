/**
 * The wire shapes of `/appointments/*`, mirroring `AppointmentResponses` and `AppointmentRequests`
 * on the server (docs/04-api-overview.md §5).
 *
 * Every instant here arrives at the **Business's** offset and every envelope names the zone, so
 * nothing on this side ever has to guess which clock a time is on — and nothing may consult the
 * browser's (ADR-0003).
 *
 * `blockedFrom` and `blockedTo` are deliberately not on the wire and are not missing by oversight.
 * They are the exclusion constraint's business; a screen showing them would present a customer with
 * ten minutes of cleanup time as if it were part of their appointment.
 */

import type { Money } from '@/lib/catalog';
import type { IsoDate, IsoInstant, Timezone } from '@/lib/time';

/**
 * The state machine, as the server's transition table declares it.
 *
 * `CONFIRMED → {COMPLETED, NO_SHOW, CANCELLED}`, and the other three are terminal. Only
 * `CONFIRMED` holds time against the exclusion constraint, which is why cancelling frees a slot
 * and completing does not.
 */
export type AppointmentStatus = 'CONFIRMED' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED';

/** Which front door booked it. The dashboard sets `DASHBOARD` and cannot be told otherwise. */
export type AppointmentSource = 'AI' | 'CLASSIC' | 'DASHBOARD';

/** Who performed a transition. `CUSTOMER` and `AI` have no user row, so `actorId` is null. */
export type ActorType = 'CUSTOMER' | 'USER' | 'AI' | 'SYSTEM';

export type AppointmentEventType =
  | 'CREATED'
  | 'RESCHEDULED'
  | 'CANCELLED'
  | 'COMPLETED'
  | 'NO_SHOW';

/** The Business is never bound by its own customer-facing deadline (CONTEXT.md). */
export type CancelledBy = 'CUSTOMER' | 'BUSINESS';

/** Name and id only — the same shape availability already returns for an Employee. */
export interface NamedRef {
  id: string;
  name: string;
}

/**
 * The Customer as an Appointment shows them.
 *
 * Phone and email are here because this is the dashboard: the business took this booking and needs
 * to reach the person. No public response reuses this shape and none may (docs/06-security.md).
 */
export interface CustomerSummary {
  id: string;
  fullName: string;
  phone: string;
  email: string | null;
}

export interface AppointmentDetail {
  id: string;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  status: AppointmentStatus;
  service: NamedRef;
  employee: NamedRef;
  customer: CustomerSummary;
  /**
   * The snapshot taken at booking, not the Service's price today. The two differ the moment an
   * owner changes a price, and this is the one that was agreed.
   */
  price: Money;
  confirmationCode: string;
  source: AppointmentSource;
  customerNote: string | null;
  cancelledAt: IsoInstant | null;
  cancelledBy: CancelledBy | null;
  cancellationReason: string | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
}

/**
 * One recorded transition.
 *
 * `payload` passes through as the `jsonb` it was stored as, because its shape genuinely differs per
 * event type — a reschedule carries two pairs of times, a cancellation carries a reason — and a
 * record with every possible field would be mostly null on every row.
 *
 * **The times inside it are UTC, not the business offset.** The recorder writes
 * `Instant.toString()`, so unlike every field on `AppointmentDetail` these have not been shifted
 * for you: render them through the envelope's `timezone` like any other instant, and never read the
 * digits in the string.
 */
export interface HistoryEntry {
  id: string;
  type: AppointmentEventType;
  actorType: ActorType;
  /** Null for a Customer and for the Receptionist, neither of which has a user row. */
  actorId: string | null;
  payload: Record<string, unknown>;
  at: IsoInstant;
}

/** One Appointment plus its audit trail. Only the detail endpoint pays for the history. */
export interface AppointmentWithHistory {
  timezone: Timezone;
  appointment: AppointmentDetail;
  history: HistoryEntry[];
}

/**
 * A page of Appointments, oldest first — the order a day is worked through.
 *
 * The pagination fields are spelled out rather than being Spring's `Page`, whose JSON shape has
 * changed between versions (docs/04-api-overview.md §2).
 */
export interface AppointmentPage {
  timezone: Timezone;
  content: AppointmentDetail[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** What `POST /appointments` answers with: the detail shape, minus a history that would be one row. */
export interface BookedAppointment {
  timezone: Timezone;
  appointment: AppointmentDetail;
}

/**
 * The question the list asks. Every field is optional, and absent means unfiltered.
 *
 * `to` is **inclusive**, matching `GET /availability`: an owner filtering "the 5th to the 5th"
 * means that day, not nothing.
 */
export interface AppointmentQuery {
  from?: IsoDate;
  to?: IsoDate;
  status?: AppointmentStatus;
  employeeId?: string;
  page?: number;
  size?: number;
}

/**
 * Booking from the dashboard.
 *
 * `employeeId` is required and comes off the Slot that was shown, never from a second resolution:
 * every Slot a client can offer already names the Employee who would perform it, and resolving one
 * again would be a second chance to answer differently from what the Customer was told.
 *
 * `startsAt` carries its offset for the same reason the server accepts an `OffsetDateTime` rather
 * than a local time — the offset the client believed it was using travels with the request and is
 * checked rather than assumed.
 */
export interface CreateAppointment {
  serviceId: string;
  employeeId: string;
  startsAt: IsoInstant;
  customerName: string;
  customerPhone: string;
  customerEmail?: string;
  customerNote?: string;
}

/** `employeeId` absent keeps the current Employee — the common case is "same person, different hour". */
export interface RescheduleAppointment {
  startsAt: IsoInstant;
  employeeId?: string;
}

export interface CancelAppointment {
  reason?: string;
}

/** `COMPLETED` or `NO_SHOW`. Cancelling has its own endpoint and its own rules. */
export type ChangeableStatus = Extract<AppointmentStatus, 'COMPLETED' | 'NO_SHOW'>;
