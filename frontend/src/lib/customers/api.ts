/**
 * The `/customers/*` calls.
 *
 * There is no create and no delete, and their absence is the design rather than a gap. A Customer
 * comes into existence by booking — which is what keeps `(business_id, phone)` an identity rather
 * than a field somebody can fill in twice — and cannot be removed while an appointment names them,
 * because the appointment history is the record a business is least able to lose.
 *
 * Nothing here takes a business id and nothing ever may (docs/04-api-overview.md §1).
 */

import { api } from '@/lib/api/client';
import type { CustomerQuery, PatchCustomer, SingleCustomer } from './types';

/** The search, expressed as a path — the question changes while the screen is open. */
export function customersPath(query: CustomerQuery): string {
  const params = new URLSearchParams();
  if (query.q) params.set('q', query.q);
  if (query.page !== undefined && query.page > 0) params.set('page', String(query.page));
  if (query.size !== undefined) params.set('size', String(query.size));

  const search = params.toString();
  return search ? `/customers?${search}` : '/customers';
}

export function customerPath(id: string): string {
  return `/customers/${id}`;
}

/** One Customer's bookings, **newest first** — the opposite of the appointment list, deliberately. */
export function customerHistoryPath(id: string, page = 0): string {
  return page > 0 ? `/customers/${id}/appointments?page=${page}` : `/customers/${id}/appointments`;
}

export const customerApi = {
  read: (id: string) => api.get<SingleCustomer>(customerPath(id)),
  patch: (id: string, patch: PatchCustomer) => api.patch<SingleCustomer>(customerPath(id), patch),
};
