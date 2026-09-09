/**
 * Business-timezone formatting.
 *
 * Every helper here **requires** an explicit IANA timezone. A business-timezone bug is therefore a
 * type error at the call site rather than a wrong time on a customer's screen: there is no overload
 * that falls back to the browser's zone, because falling back is the defect (ADR-0003).
 *
 * ESLint additionally forbids `Intl.DateTimeFormat` and the `toLocale*String` family outside this
 * module, so the rule is enforced rather than remembered.
 */

/** An IANA timezone id, e.g. `Asia/Tbilisi`. Comes from the business, never from the browser. */
export type Timezone = string;

/** An ISO-8601 instant with an offset, e.g. `2026-09-08T17:00:00+04:00`. */
export type IsoInstant = string;

/** A calendar date, `YYYY-MM-DD`, always meaning a date **in the business timezone**. */
export type IsoDate = string;

const FORMAT_LOCALE = 'en-GB';

function parse(instant: IsoInstant | Date): Date {
  const date = instant instanceof Date ? instant : new Date(instant);
  if (Number.isNaN(date.getTime())) {
    throw new TypeError(`Not a valid instant: ${String(instant)}`);
  }
  return date;
}

function format(
  instant: IsoInstant | Date,
  timezone: Timezone,
  options: Intl.DateTimeFormatOptions,
): string {
  // eslint-disable-next-line no-restricted-properties -- the one permitted construction site
  return new Intl.DateTimeFormat(FORMAT_LOCALE, { ...options, timeZone: timezone }).format(
    parse(instant),
  );
}

/** `17:00` */
export function formatTime(instant: IsoInstant | Date, timezone: Timezone): string {
  return format(instant, timezone, { hour: '2-digit', minute: '2-digit', hour12: false });
}

const MONTHS = [
  'January',
  'February',
  'March',
  'April',
  'May',
  'June',
  'July',
  'August',
  'September',
  'October',
  'November',
  'December',
];

/**
 * `2026-12-24` → `24 December 2026`.
 *
 * The one helper here that takes no timezone, and deliberately: an `IsoDate` has already been
 * resolved into the business's zone by whoever produced it, so there is nothing left to interpret.
 * Asking for a zone would invite the caller to supply one and imply a conversion that must not
 * happen twice.
 *
 * Built from the string's own parts rather than through a formatter. `new Date('2026-12-24')`
 * parses as UTC midnight, which renders as the 23rd for any business west of Greenwich — the exact
 * off-by-one-day this module exists to prevent.
 */
export function formatIsoDate(date: IsoDate): string {
  const [year, month, day] = date.split('-');
  const name = MONTHS[Number(month) - 1];
  if (!year || !day || !name) return date;
  return `${Number(day)} ${name} ${year}`;
}

/**
 * Which day of the week an `IsoDate` falls on — 1 is Monday, 7 is Sunday.
 *
 * ISO-8601 numbering, matching `java.time.DayOfWeek` and the `dayOfWeek` on the wire, rather than
 * `Date`'s own 0-is-Sunday. Returning the platform's numbering would put an off-by-one between
 * this function and every row it is used to match.
 *
 * Parsed at UTC midnight and read back in UTC, which is the only pair that cancels out: an
 * `IsoDate` has already been resolved into the business's zone by whoever produced it, so there is
 * nothing left to convert and any conversion here would be the second one. Reading it back with
 * `getDay()` instead would re-interpret it in the *browser's* zone and answer with the wrong
 * weekday for half of every day — the same off-by-one-day `formatIsoDate` avoids, arrived at from
 * the other side.
 */
export function isoDateWeekday(date: IsoDate): number {
  const parsed = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    throw new TypeError(`Not a valid date: ${date}`);
  }
  const day = parsed.getUTCDay();
  return day === 0 ? 7 : day;
}

/**
 * `2026-09-09` plus a number of days, as an `IsoDate`. Negative moves back.
 *
 * Calendar arithmetic, not instant arithmetic — which is why it is safe to do at UTC midnight
 * despite ADR-0003. Adding 24 hours to an *instant* in a zone that changes offset that night
 * lands an hour out; adding a day to a *date* does not, because a date has no offset to change.
 * UTC is chosen precisely because it never has one, so the arithmetic cannot be perturbed by the
 * business's DST or the browser's.
 */
export function addIsoDays(date: IsoDate, days: number): IsoDate {
  const parsed = new Date(`${date}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) {
    throw new TypeError(`Not a valid date: ${date}`);
  }
  parsed.setUTCDate(parsed.getUTCDate() + days);
  return parsed.toISOString().slice(0, 10);
}

/** `8 September 2026` */
export function formatDate(instant: IsoInstant | Date, timezone: Timezone): string {
  return format(instant, timezone, { day: 'numeric', month: 'long', year: 'numeric' });
}

/** `Tue 8 Sep` */
export function formatShortDate(instant: IsoInstant | Date, timezone: Timezone): string {
  return format(instant, timezone, { weekday: 'short', day: 'numeric', month: 'short' });
}

/** `Tue 8 Sep 2026, 17:00` */
export function formatDateTime(instant: IsoInstant | Date, timezone: Timezone): string {
  return format(instant, timezone, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

/** `17:00 – 17:45`, for a slot or an appointment. */
export function formatTimeRange(
  start: IsoInstant | Date,
  end: IsoInstant | Date,
  timezone: Timezone,
): string {
  return `${formatTime(start, timezone)} – ${formatTime(end, timezone)}`;
}

/**
 * The calendar date an instant falls on **in the business timezone** — `YYYY-MM-DD`.
 *
 * Built from formatted parts rather than from `toISOString()`, which would answer in UTC and be
 * off by a day for a business east or west of it for part of every day.
 */
export function toBusinessDate(instant: IsoInstant | Date, timezone: Timezone): IsoDate {
  // eslint-disable-next-line no-restricted-properties -- the one permitted construction site
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(parse(instant));

  const value = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? '';

  return `${value('year')}-${value('month')}-${value('day')}`;
}

/** Whether two instants fall on the same calendar date in the business timezone. */
export function isSameBusinessDate(
  a: IsoInstant | Date,
  b: IsoInstant | Date,
  timezone: Timezone,
): boolean {
  return toBusinessDate(a, timezone) === toBusinessDate(b, timezone);
}

/** `IANA` validity check, for a timezone arriving from configuration. */
export function isValidTimezone(timezone: string): boolean {
  try {
    // eslint-disable-next-line no-restricted-properties -- the one permitted construction site
    new Intl.DateTimeFormat('en-GB', { timeZone: timezone });
    return true;
  } catch {
    return false;
  }
}

/** `60.00 GEL` — money always arrives as a decimal string, never a float. */
export function formatMoney(amount: string, currency: string): string {
  return `${amount} ${currency}`;
}
