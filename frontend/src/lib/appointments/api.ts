/**
 * The `/appointments/*` calls, named once so no screen writes a path string.
 *
 * Nothing here takes a business id and nothing ever may: the tenant is a claim in the access
 * cookie (docs/04-api-overview.md §1).
 *
 * **Reads are paths and writes are calls**, which is the split `lib/scheduling` established. A read
 * whose question changes while the screen is open has to be expressible as a string, because that
 * string is what a keyed component re-runs on; a write happens once, in response to a click, and
 * has nothing to key.
 */

import { api } from '@/lib/api/client';
import type {
  AppointmentPage,
  AppointmentQuery,
  AppointmentWithHistory,
  BookedAppointment,
  CancelAppointment,
  ChangeableStatus,
  CreateAppointment,
  RescheduleAppointment,
} from './types';

/**
 * The filtered list, expressed as a path.
 *
 * The four filters and the page number are all part of the question, so all five belong in the
 * string. A filter the owner cleared is left out entirely rather than sent empty: `status=` is not
 * how the server spells "any status", and sending it would be a different question than the one
 * being asked.
 */
export function appointmentsPath(query: AppointmentQuery): string {
  const params = new URLSearchParams();
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.status) params.set('status', query.status);
  if (query.employeeId) params.set('employeeId', query.employeeId);
  if (query.page !== undefined && query.page > 0) params.set('page', String(query.page));
  if (query.size !== undefined) params.set('size', String(query.size));

  const search = params.toString();
  return search ? `/appointments?${search}` : '/appointments';
}

/** One appointment and its audit trail. */
export function appointmentPath(id: string): string {
  return `/appointments/${id}`;
}

export const appointmentApi = {
  read: (id: string) => api.get<AppointmentWithHistory>(appointmentPath(id)),
  list: (query: AppointmentQuery) => api.get<AppointmentPage>(appointmentsPath(query)),

  /**
   * Books. `409 SLOT_UNAVAILABLE` is the answer that matters: the exclusion constraint refused it,
   * which means somebody else took the slot between the list being drawn and this call being made.
   * A caller must re-read availability rather than retry (docs/adr/0002).
   */
  book: (appointment: CreateAppointment) =>
    api.post<BookedAppointment>('/appointments', appointment),

  /**
   * Cancels, as the business. The Cancellation Window does not apply — a business is never bound by
   * its own customer-facing deadline (CONTEXT.md) — and cancelling twice is a `200` that changes
   * nothing and keeps the first reason.
   */
  cancel: (id: string, body: CancelAppointment = {}) =>
    api.post<AppointmentWithHistory>(`/appointments/${id}/cancel`, body),

  /**
   * Moves one, keeping its id and Confirmation Code.
   *
   * Two `409`s are possible and they mean different things: `SLOT_UNAVAILABLE` is the new time
   * being taken, and `VERSION_CONFLICT` is this appointment having been changed by someone else
   * since it was read.
   */
  reschedule: (id: string, body: RescheduleAppointment) =>
    api.post<AppointmentWithHistory>(`/appointments/${id}/reschedule`, body),

  /**
   * `COMPLETED` or `NO_SHOW` only. Cancelling is refused here with a field error pointing at the
   * cancel action, because it carries three things this shape cannot express — a cancelling party,
   * a window check and idempotence.
   */
  changeStatus: (id: string, status: ChangeableStatus) =>
    api.post<AppointmentWithHistory>(`/appointments/${id}/status`, { status }),
};
