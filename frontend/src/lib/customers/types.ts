/**
 * The wire shapes of `/customers/*`, mirroring `CustomerResponses` and `CustomerRequests` on the
 * server (docs/04-api-overview.md §5).
 *
 * Contact details are present because this is the dashboard: the business took the booking and
 * needs to reach the person. No public response reuses these shapes, and none may — a Customer's
 * phone number is the thing a public endpoint must never hand out (docs/06-security.md).
 */

import type { IsoInstant } from '@/lib/time';

export interface CustomerDetail {
  id: string;
  fullName: string;
  /** Stored in E.164. Half the identity key, which is why nothing can edit it. */
  phone: string;
  email: string | null;
  /** Every booking in any status, not just the ones that still hold time. */
  totalAppointments: number;
  /**
   * The **latest** `startsAt` of any booking, in any status — `max(startsAt)`, not the last one
   * that went ahead. For a customer with something coming up it is a date in the future, so it
   * cannot be labelled "last visit" without being wrong about exactly the customers who matter.
   */
  lastAppointmentAt: IsoInstant | null;
  createdAt: IsoInstant;
}

export interface CustomerPage {
  content: CustomerDetail[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface SingleCustomer {
  customer: CustomerDetail;
}

/**
 * Correcting a name or an email.
 *
 * **No phone.** It is half of `(business_id, phone)`, so changing it would either collide with
 * another Customer or silently move one person's history onto a number belonging to somebody else.
 * Booking under the right number creates the right Customer, which is the supported fix and the one
 * that leaves both histories intact.
 */
export interface PatchCustomer {
  fullName?: string;
  email?: string;
}

/** `q` matches name, phone or email; absent lists everyone. */
export interface CustomerQuery {
  q?: string;
  page?: number;
  size?: number;
}
