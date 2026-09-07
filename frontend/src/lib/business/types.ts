/**
 * The wire shapes of `/business/*`, mirroring `BusinessResponses` and `BusinessRequests` on the
 * server (docs/04-api-overview.md §5).
 *
 * Optional text columns are `string | null`, not `string | undefined`: the server sends the key
 * with a null value, and the difference matters on the way back — `null` in a PATCH would mean
 * "leave alone", while `''` is what clears the field.
 */

import type { IsoDate, IsoInstant } from '@/lib/time';

/** `HH:mm`, a wall-clock time in the business timezone. What `<input type="time">` speaks. */
export type WallClockTime = string;

/** ISO-8601 weekday numbering: 1 is Monday, 7 is Sunday. Matches `java.time.DayOfWeek`. */
export type DayOfWeek = 1 | 2 | 3 | 4 | 5 | 6 | 7;

export interface BusinessProfile {
  id: string;
  name: string;
  slug: string;
  timezone: string;
  currency: string;
  description: string | null;
  addressLine: string | null;
  city: string | null;
  country: string | null;
  phone: string | null;
  email: string | null;
  website: string | null;
  slotIntervalMinutes: number;
  minLeadTimeMinutes: number;
  maxAdvanceDays: number;
  cancellationWindowHours: number;
  cancellationPolicy: string | null;
  aiEnabled: boolean;
  aiAdditionalInfo: string | null;
  aiDailyCostCapCents: number;
  bookingUrl: string;
  updatedAt: IsoInstant;
}

/**
 * A partial update. An absent key leaves the field alone, `''` clears an optional one, and a value
 * sets it — the rule stated once on the server in `Business.apply`.
 */
export type BusinessPatch = Partial<{
  name: string;
  slug: string;
  timezone: string;
  currency: string;
  description: string;
  addressLine: string;
  city: string;
  country: string;
  phone: string;
  email: string;
  website: string;
  slotIntervalMinutes: number;
  minLeadTimeMinutes: number;
  maxAdvanceDays: number;
  cancellationWindowHours: number;
  cancellationPolicy: string;
  aiEnabled: boolean;
  aiAdditionalInfo: string;
  aiDailyCostCapCents: number;
}>;

export interface DayHours {
  id: string;
  dayOfWeek: DayOfWeek;
  opensAt: WallClockTime;
  closesAt: WallClockTime;
}

/** The zone travels with the times, because `09:00` means nothing without it. */
export interface WeekHours {
  timezone: string;
  hours: DayHours[];
}

/** One interval as submitted. A day with no interval here is closed. */
export interface HoursInterval {
  dayOfWeek: DayOfWeek;
  opensAt: WallClockTime;
  closesAt: WallClockTime;
}

/**
 * A closure, carrying both the stored instants and the local dates the owner entered, so nothing
 * needs re-deriving on this side.
 */
export interface Closure {
  id: string;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  startDate: IsoDate;
  /** Inclusive — the last day the business is closed, which is what the owner typed. */
  endDate: IsoDate;
  reason: string | null;
}

export interface ClosureList {
  timezone: string;
  closures: Closure[];
}

export interface CreateClosure {
  startDate: IsoDate;
  endDate: IsoDate;
  reason?: string;
}

/** The appointments the new closure covers. Nothing is cancelled; the owner is told (FR-2). */
export interface CreatedClosure {
  closure: Closure;
  affectedAppointments: number;
}

export interface Faq {
  id: string;
  question: string;
  answer: string;
  sortOrder: number;
}

export interface FaqList {
  faqs: Faq[];
}

export interface CreateFaq {
  question: string;
  answer: string;
  /** Absent appends. The caller should not have to count the list to say "at the end". */
  sortOrder?: number;
}

export type FaqPatch = Partial<{
  question: string;
  answer: string;
  sortOrder: number;
}>;

/** Derived on every read, never stored, so it cannot drift from reality. */
export interface Onboarding {
  hoursConfigured: boolean;
  hasActiveService: boolean;
  hasActiveEmployee: boolean;
  hasEmployeeSchedule: boolean;
  hasBookableService: boolean;
  publicPageReady: boolean;
  bookingUrl: string;
}
