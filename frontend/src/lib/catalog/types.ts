/**
 * The wire shapes of `/services/*`, mirroring `ServiceResponses` and `ServiceRequests` on the
 * server (docs/04-api-overview.md §5).
 *
 * Optional text is `string | null`, not `string | undefined`: the server sends the key with a null
 * value, and the difference matters on the way back — `null` in a PATCH means "leave alone", while
 * `''` clears the field.
 */

import type { IsoInstant } from '@/lib/time';

/**
 * An amount and the code it is denominated in, never a float.
 *
 * The currency travels with every price rather than being read once for the business, because a
 * Service is stamped with the currency it was created in and is never re-stamped: a business that
 * switches from USD to GEL still has services priced in USD until the owner edits them. A screen
 * that assumed one currency for the whole list would mislabel exactly those rows.
 */
export interface Money {
  /** A decimal string — `60.00`. Parsing it into a number would make it a binary float. */
  amount: string;
  currency: string;
}

export interface ServiceDetail {
  id: string;
  name: string;
  description: string | null;
  durationMinutes: number;
  bufferBeforeMinutes: number;
  bufferAfterMinutes: number;
  price: Money;
  active: boolean;
  /** Who may perform this. On the list as well as the detail, so no screen needs a request per row. */
  employeeIds: string[];
  updatedAt: IsoInstant;
}

export interface ServiceList {
  services: ServiceDetail[];
}

/** The currency is the business's and is never sent: the server stamps it. */
export interface CreateService {
  name: string;
  description?: string;
  durationMinutes: number;
  bufferBeforeMinutes?: number;
  bufferAfterMinutes?: number;
  /** A decimal string, for the same reason `Money.amount` is one. */
  price: string;
}

export type ServicePatch = Partial<{
  name: string;
  description: string;
  durationMinutes: number;
  bufferBeforeMinutes: number;
  bufferAfterMinutes: number;
  price: string;
}>;

/**
 * The result of an activate or a deactivate.
 *
 * Nothing is auto-cancelled; the count is what lets an owner decide deliberately (FR-2). It is
 * always `0` for an activation, and — until appointments exist — for a deactivation too.
 */
export interface ServiceBookabilityChange {
  service: ServiceDetail;
  affectedFutureAppointments: number;
}

/** The set as it now stands, so a client never assumes its own submission was taken whole. */
export interface AssignedEmployees {
  employeeIds: string[];
}
