/**
 * Reads and the one write on `/public/*` — everything reachable without an account.
 *
 * **Every path is built here and nowhere else**, because every one of them carries a slug that
 * arrived from the URL bar. A slug is a path *segment*, so it is encoded rather than interpolated:
 * `SlugService` only ever produces `[a-z0-9-]`, but nothing stops a visitor typing something else
 * into the address bar, and an unencoded `/` or `..` in a segment is a request to a path this
 * application did not mean to make. Encoding turns that into a `404` from the slug filter, which
 * is the correct answer to a slug that does not exist.
 *
 * The reads are expressed as **paths** rather than as calls, matching `lib/scheduling`: the
 * availability question changes while the page is open, and `useResource` re-runs because the
 * string moved and for no other reason.
 */

import { api } from '@/lib/api/client';
import type {
  BookedAppointment,
  CancelPublicAppointment,
  CreatePublicAppointment,
  ManageAvailabilityQuery,
  ManagedAppointment,
  PublicAvailabilityQuery,
  PublicBusiness,
  PublicService,
  ReschedulePublicAppointment,
} from './types';

function segment(slug: string): string {
  return encodeURIComponent(slug);
}

export function publicBusinessPath(slug: string): string {
  return `/public/businesses/${segment(slug)}`;
}

export function publicServicesPath(slug: string): string {
  return `${publicBusinessPath(slug)}/services`;
}

/**
 * Who could perform a Service.
 *
 * Given a `serviceId` the list is the active Employees assigned to it, which is the only list
 * worth showing: naming anyone else produces `EMPLOYEE_CANNOT_PERFORM_SERVICE` at the *end* of the
 * flow, after the Customer has entered their details, rather than at the start.
 */
export function publicEmployeesPath(slug: string, serviceId?: string): string {
  const path = `${publicBusinessPath(slug)}/employees`;
  return serviceId ? `${path}?serviceId=${encodeURIComponent(serviceId)}` : path;
}

export function publicAvailabilityPath(slug: string, query: PublicAvailabilityQuery): string {
  const params = new URLSearchParams({
    serviceId: query.serviceId,
    from: query.from,
    to: query.to,
  });
  if (query.employeeId) params.set('employeeId', query.employeeId);
  return `${publicBusinessPath(slug)}/availability?${params.toString()}`;
}

/**
 * Resolving a Manage Link.
 *
 * **No slug**, and that is the design rather than an inconsistency: a Customer following a link
 * out of an email does not know which slug their appointment is under, and requiring one would be
 * a second identifier that could disagree with the token. The server derives the tenant from the
 * proof instead.
 *
 * The token goes in the query string rather than the path because that is the shape the endpoint
 * publishes — and `URLSearchParams` encodes it, which matters more here than for a slug: this is
 * an opaque signed string, and a `+` in a base64 alphabet means a space in a URL.
 */
export function managePath(token: string): string {
  return `/public/appointments/manage?${new URLSearchParams({ token }).toString()}`;
}

/**
 * The Slots a rescheduling Customer may move to.
 *
 * A different endpoint from `publicAvailabilityPath`, and the difference is load-bearing: this one
 * excludes the appointment being moved. The Customer's own booking blocks the time it occupies and
 * the Buffers around it, so the ordinary grid would refuse to offer them a slot fifteen minutes
 * later — and the ordinary grid deliberately takes no exclusion parameter, because diffing the two
 * answers would tell an anonymous caller that some appointment exists.
 */
export function manageAvailabilityPath(query: ManageAvailabilityQuery): string {
  const params = new URLSearchParams({
    token: query.token,
    from: query.from,
    to: query.to,
  });
  if (query.employeeId) params.set('employeeId', query.employeeId);
  return `/public/appointments/manage/availability?${params.toString()}`;
}

export const publicApi = {
  /**
   * The page's own reads, run together.
   *
   * Called from a server component, where the client resolves `BACKEND_INTERNAL_URL` on its own
   * and no cookie is forwarded — so the profile and catalogue rendered into the HTML are the ones
   * a stranger would get, even when the owner is the one looking at their own booking page. That
   * is worth having by construction: a page that showed its owner more than it shows a visitor is
   * a page whose response minimisation nobody would notice was broken.
   */
  page: (slug: string): Promise<[PublicBusiness, PublicService[]]> =>
    Promise.all([
      api.get<PublicBusiness>(publicBusinessPath(slug)),
      api.get<PublicService[]>(publicServicesPath(slug)),
    ]),

  book: (slug: string, body: CreatePublicAppointment) =>
    api.post<BookedAppointment>(`${publicBusinessPath(slug)}/appointments`, body),

  /** What the Manage Link resolves to. Read from the server component that renders the page. */
  manage: (token: string) => api.get<ManagedAppointment>(managePath(token)),

  /**
   * Cancel and reschedule, both addressed by appointment id and both authorised by the body.
   *
   * The id in the path is a claim and the proof in the body is what decides: the server resolves
   * an appointment from the `authority` on its own and then checks the path id against it, so a
   * token for appointment A presented on B's path is a `404`. Passing the id from the resolved
   * appointment — never from anywhere else — is what keeps the two agreeing.
   */
  cancel: (id: string, body: CancelPublicAppointment) =>
    api.post<ManagedAppointment>(`/public/appointments/${id}/cancel`, body),

  reschedule: (id: string, body: ReschedulePublicAppointment) =>
    api.post<ManagedAppointment>(`/public/appointments/${id}/reschedule`, body),
};
