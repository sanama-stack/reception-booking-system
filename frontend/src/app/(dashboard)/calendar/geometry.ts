/**
 * Where a block goes, and how tall it is.
 *
 * The whole calendar is one arithmetic: a minute past midnight is a pixel offset, and a duration
 * is a height. Keeping that arithmetic here — pure, with no React and no fetching — is what makes
 * "a 150-minute colour looks three times a 50-minute one" a property of two functions rather than
 * something four components have to agree about.
 *
 * **Every minute in this file is a minute in the business timezone**, because every caller reads
 * it through `lib/time` with the envelope's zone (ADR-0003). Nothing here consults a clock.
 */

import {
  addIsoDays,
  isoDateWeekday,
  minutesOfDay,
  toBusinessDate,
  type IsoDate,
  type IsoInstant,
  type Timezone,
} from '@/lib/time';

export const DAY_MINUTES = 24 * 60;

/** A span of one calendar day, in minutes past that day's midnight. */
export interface Span {
  startMinute: number;
  endMinute: number;
}

/**
 * The part of an instant span that falls on one calendar date, or `null` if none of it does.
 *
 * Closures are the reason this clips rather than assuming one day: a business shut for a week is
 * **one row** with one pair of instants, and every day of that week has to draw it. Appointments
 * cannot currently cross midnight — `AvailabilityEngine` requires the whole booking to fit inside
 * one of the date's opening intervals, and intervals do not span days — so for them this is the
 * identity. It is written the general way anyway, so the day overnight hours become expressible
 * the view does not start silently dropping the blocks that straddle the boundary.
 *
 * A span ending exactly at midnight is **not** drawn on the day it ends. Its last minute belongs
 * to the day before, and a zero-height block on the following morning is a lie about a business
 * that is open.
 */
export function spanOn(
  date: IsoDate,
  startsAt: IsoInstant,
  endsAt: IsoInstant,
  timezone: Timezone,
): Span | null {
  const first = toBusinessDate(startsAt, timezone);
  const last = toBusinessDate(endsAt, timezone);
  // ISO dates are fixed-width and zero-padded, so lexical order is calendar order.
  if (date < first || date > last) return null;

  const startMinute = date === first ? minutesOfDay(startsAt, timezone) : 0;
  const endMinute = date === last ? minutesOfDay(endsAt, timezone) : DAY_MINUTES;
  return endMinute <= startMinute ? null : { startMinute, endMinute };
}

/** Every calendar date from `from` to `to`, both inclusive. */
export function daysBetween(from: IsoDate, to: IsoDate): IsoDate[] {
  const days: IsoDate[] = [];
  for (let day = from; day <= to; day = addIsoDays(day, 1)) days.push(day);
  return days;
}

/** One item, told where to sit across the width of its column. */
export interface Placed<T> {
  item: T;
  span: Span;
  /** Which sub-column this one takes, from 0. */
  lane: number;
  /** How many sub-columns the overlapping group it belongs to needs. */
  lanes: number;
}

/**
 * Side-by-side placement for things that overlap in time.
 *
 * Two appointments never overlap for the same **employee** — the exclusion constraint refuses it —
 * so a day-view column packs to one lane almost always. The week view is where this earns its
 * keep: a day column holds everybody, and a salon with three chairs has three blocks at 10:00 that
 * would otherwise be drawn exactly on top of each other, hiding two bookings behind the third.
 *
 * Lanes are counted **per overlapping cluster**, not per column. One pair of overlapping
 * appointments at 09:00 would otherwise squeeze every other block on the day to half width for a
 * collision it is not part of.
 */
export function placeSideBySide<T>(items: Array<{ item: T; span: Span }>): Array<Placed<T>> {
  const sorted = [...items].sort(
    (a, b) => a.span.startMinute - b.span.startMinute || a.span.endMinute - b.span.endMinute,
  );

  const placed: Array<Placed<T>> = [];
  let cluster: Array<Placed<T>> = [];
  let clusterEnd = -1;
  const laneEnds: number[] = [];

  /** A cluster is closed the moment a block starts after everything before it has ended. */
  function closeCluster() {
    for (const entry of cluster) entry.lanes = laneEnds.length;
    placed.push(...cluster);
    cluster = [];
    laneEnds.length = 0;
    clusterEnd = -1;
  }

  for (const { item, span } of sorted) {
    if (cluster.length > 0 && span.startMinute >= clusterEnd) closeCluster();

    let lane = laneEnds.findIndex((end) => end <= span.startMinute);
    if (lane === -1) {
      lane = laneEnds.length;
      laneEnds.push(span.endMinute);
    } else {
      laneEnds[lane] = span.endMinute;
    }

    cluster.push({ item, span, lane, lanes: 1 });
    clusterEnd = Math.max(clusterEnd, span.endMinute);
  }
  if (cluster.length > 0) closeCluster();

  return placed;
}

/** The hours the grid draws, as minutes past midnight. */
export interface GridWindow {
  startMinute: number;
  endMinute: number;
}

/**
 * The window the grid shows, whole hours, wide enough that **nothing in view is off-screen**.
 *
 * Opening hours alone would be the obvious choice and it is the wrong one: the dashboard can book
 * outside them, an owner can shorten their hours after taking a booking, and time off and closures
 * are frequently overnight. A grid that cropped to opening hours would answer "there is nothing at
 * 07:00" for a business with somebody sitting in the chair at 07:00, which is the one thing a
 * calendar may never do. So the hours are a floor for the window, and everything drawn widens it.
 *
 * The fallback is a working day rather than a full 24 hours: a business with no hours set and
 * nothing booked gets a grid it can read, not 1 440 minutes of empty rows.
 */
const FALLBACK: GridWindow = { startMinute: 8 * 60, endMinute: 20 * 60 };

export function gridWindow(spans: Span[], openingHours: Span[]): GridWindow {
  const all = [...spans, ...openingHours];
  if (all.length === 0) return FALLBACK;

  const earliest = Math.min(...all.map((span) => span.startMinute));
  const latest = Math.max(...all.map((span) => span.endMinute));

  const startMinute = Math.floor(earliest / 60) * 60;
  const endMinute = Math.min(DAY_MINUTES, Math.ceil(latest / 60) * 60);

  // Never narrower than an hour, so a business whose only content is a single 30-minute booking
  // still gets a grid with a row in it rather than a window shorter than its own label.
  return {
    startMinute: Math.max(0, startMinute),
    endMinute: Math.max(endMinute, startMinute + 60),
  };
}

/** `09:30` from a minute past midnight, for the gutter labels. No timezone: already local. */
export function minuteLabel(minute: number): string {
  const hour = Math.floor(minute / 60) % 24;
  const rest = minute % 60;
  return `${String(hour).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}

/** `09:00`–`17:30` wall-clock strings, as the hours endpoint sends them, into a `Span`. */
export function wallClockSpan(opensAt: string, closesAt: string): Span {
  return { startMinute: toMinutes(opensAt), endMinute: toMinutes(closesAt) };
}

function toMinutes(wallClock: string): number {
  const [hour, minute] = wallClock.split(':');
  return Number(hour) * 60 + Number(minute ?? 0);
}

/**
 * Where the current-time line goes on a given day, or `null` when it must not be drawn.
 *
 * Two questions with one answer, deliberately. Asked separately — "is today in view?" in one place
 * and "what minute is it?" in another — a business open until 22:00 gets a line pinned to the
 * bottom of its grid all night, still claiming to be now; and a view of next week gets one pinned
 * to the top. The line means *this is the moment you are in*, so a view that does not contain that
 * moment gets no line at all.
 */
export function currentMinute(
  now: Date,
  timezone: Timezone,
  date: IsoDate,
  window: GridWindow,
): number | null {
  if (toBusinessDate(now, timezone) !== date) return null;
  const minute = minutesOfDay(now, timezone);
  return minute < window.startMinute || minute > window.endMinute ? null : minute;
}

/**
 * The Monday-to-Sunday week a date falls in.
 *
 * ISO-8601 for everybody, not the viewer's locale — the same decision the analytics endpoint takes
 * for its `thisWeek` period, and taken the same way for the same reason: a locale-dependent week
 * start makes one business's week differ by who is looking at it, which is worse than being
 * unfamiliar to some of them. The two have to agree, or the dashboard's "this week" counts a
 * different seven days than the calendar draws.
 */
export function weekOf(date: IsoDate): { from: IsoDate; to: IsoDate } {
  const from = addIsoDays(date, -(isoDateWeekday(date) - 1));
  return { from, to: addIsoDays(from, 6) };
}
