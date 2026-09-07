import type { DayOfWeek } from './types';

/**
 * The week, Monday first, matching ISO-8601 and `java.time.DayOfWeek` so a day number means the
 * same thing on both sides of the wire.
 *
 * Written out rather than derived from a formatter. `lib/time` exists because a date rendered in
 * the wrong zone is a defect, and a weekday *label* has no instant to render — asking
 * `Intl.DateTimeFormat` for one would mean inventing a date to format, in some zone, to recover a
 * string already known here.
 */
export const DAYS: ReadonlyArray<{ value: DayOfWeek; label: string; short: string }> = [
  { value: 1, label: 'Monday', short: 'Mon' },
  { value: 2, label: 'Tuesday', short: 'Tue' },
  { value: 3, label: 'Wednesday', short: 'Wed' },
  { value: 4, label: 'Thursday', short: 'Thu' },
  { value: 5, label: 'Friday', short: 'Fri' },
  { value: 6, label: 'Saturday', short: 'Sat' },
  { value: 7, label: 'Sunday', short: 'Sun' },
];

export function dayLabel(day: DayOfWeek): string {
  return DAYS.find((entry) => entry.value === day)?.label ?? String(day);
}
