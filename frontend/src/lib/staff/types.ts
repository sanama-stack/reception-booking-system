/**
 * The wire shapes of `/employees/*`, mirroring `EmployeeResponses` and `EmployeeRequests` on the
 * server (docs/04-api-overview.md §5).
 */

import type { DayOfWeek, WallClockTime } from '@/lib/business';
import type { IsoDate, IsoInstant } from '@/lib/time';

export interface EmployeeDetail {
  id: string;
  fullName: string;
  email: string | null;
  /** Stored in E.164 — the server normalises what was typed against the business's country. */
  phone: string | null;
  jobTitle: string | null;
  active: boolean;
  /** What this person may perform. On the list as well as the detail, so no request per row. */
  serviceIds: string[];
  updatedAt: IsoInstant;
}

export interface EmployeeList {
  employees: EmployeeDetail[];
}

export interface CreateEmployee {
  fullName: string;
  email?: string;
  phone?: string;
  jobTitle?: string;
}

export type EmployeePatch = Partial<{
  fullName: string;
  email: string;
  phone: string;
  jobTitle: string;
}>;

/** Nothing is auto-cancelled; the count is what lets a deactivation be taken deliberately. */
export interface EmployeeBookabilityChange {
  employee: EmployeeDetail;
  affectedFutureAppointments: number;
}

export interface AssignedServices {
  serviceIds: string[];
}

/**
 * One interval of a Working Schedule.
 *
 * The same shape as `DayHours` with three field names changed — `startsAt`/`endsAt` rather than
 * `opensAt`/`closesAt` — because it is the same kind of fact about a different subject. Phase 05
 * intersects the two.
 */
export interface ScheduleInterval {
  id: string;
  dayOfWeek: DayOfWeek;
  startsAt: WallClockTime;
  endsAt: WallClockTime;
}

/** The zone travels with the times, because `09:00` means nothing without it. */
export interface WeekSchedule {
  timezone: string;
  schedule: ScheduleInterval[];
}

/** One interval as submitted. A day with no interval here is a day this person does not work. */
export interface ScheduleSubmission {
  dayOfWeek: DayOfWeek;
  startsAt: WallClockTime;
  endsAt: WallClockTime;
}

/**
 * An absence, carrying both the stored instants and the local dates the owner entered, so nothing
 * needs re-deriving on this side.
 */
export interface TimeOff {
  id: string;
  startsAt: IsoInstant;
  endsAt: IsoInstant;
  startDate: IsoDate;
  /** Inclusive — the last day off, which is what the owner typed. */
  endDate: IsoDate;
  reason: string | null;
}

export interface TimeOffList {
  timezone: string;
  timeOff: TimeOff[];
}

export interface CreateTimeOff {
  startDate: IsoDate;
  endDate: IsoDate;
  reason?: string;
}
