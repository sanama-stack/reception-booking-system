import { describe, expect, it } from 'vitest';
import {
  addIsoDays,
  formatIsoDate,
  formatTime,
  formatTimeRange,
  isoDateWeekday,
  minutesOfDay,
  toBusinessDate,
} from './index';

/**
 * `lib/time` under a browser whose clock is not the business's — ADR-0003.
 *
 * The suite runs at `Asia/Tbilisi`, +04:00 with no DST (`vitest.config.mts`). Every test here
 * formats an instant for a business at **UTC**, so a helper that quietly fell back to the ambient
 * zone would answer four hours late and be caught. Run the same file at UTC and most of it would
 * pass against a module that did nothing at all.
 */

/** 09:00 UTC — the phase document's own example, on a Tuesday. */
const NINE_UTC = '2026-03-10T09:00:00Z';

describe('the environment these tests depend on', () => {
  /**
   * The counterfactual, asserted rather than assumed.
   *
   * Everything below is the difference between reading an instant in the business's zone and
   * reading it in the browser's. If `TZ` ever stopped reaching the worker, that difference would
   * be zero, every assertion below would still pass, and the suite would be a tautology
   * announcing itself as a timezone test. This is the one assertion that fails when that happens.
   */
  it('runs four hours ahead of the business, so a fallback to the browser would show', () => {
    expect(new Date(NINE_UTC).getHours(), 'the ambient zone is not +04:00').toBe(13);
    // Minutes *behind* UTC, so +04:00 is -240. Asserted through `Date` rather than by reading
    // back `Intl.DateTimeFormat().resolvedOptions()`, which ESLint restricts application-wide for
    // good reason (ADR-0003) — and the offset is the thing that matters here anyway. A zone named
    // correctly but not in force would pass the name check and fail this one.
    expect(new Date(NINE_UTC).getTimezoneOffset()).toBe(-240);
  });
});

describe('formatTime', () => {
  it("draws a UTC business's 09:00Z at 09:00, not at the browser's 13:00", () => {
    expect(formatTime(NINE_UTC, 'UTC')).toBe('09:00');
  });

  it('reads the same instant differently for a business that really is at +04:00', () => {
    expect(formatTime(NINE_UTC, 'Asia/Tbilisi')).toBe('13:00');
  });

  it('follows the business across a DST change the browser does not have', () => {
    // Berlin is +01:00 on 28 March 2026 and +02:00 on 30 March. Tbilisi changes on neither.
    expect(formatTime('2026-03-28T09:00:00Z', 'Europe/Berlin')).toBe('10:00');
    expect(formatTime('2026-03-30T09:00:00Z', 'Europe/Berlin')).toBe('11:00');
  });
});

describe('formatTimeRange', () => {
  it('renders both ends in the business zone', () => {
    expect(formatTimeRange(NINE_UTC, '2026-03-10T09:45:00Z', 'UTC')).toBe('09:00 – 09:45');
  });
});

describe('minutesOfDay', () => {
  /** The calendar's whole geometry: this number is a block's top edge and its height. */
  it('answers 540 for 09:00Z at UTC, and 780 for the browser reading of the same instant', () => {
    expect(minutesOfDay(NINE_UTC, 'UTC')).toBe(540);
    expect(minutesOfDay(NINE_UTC, 'Asia/Tbilisi')).toBe(780);
    // 240 minutes apart, which is the four hours the block would be drawn out by.
    expect(minutesOfDay(NINE_UTC, 'Asia/Tbilisi') - minutesOfDay(NINE_UTC, 'UTC')).toBe(240);
  });
});

describe('toBusinessDate', () => {
  /**
   * The off-by-one-day, from both sides. At 23:00 UTC it is already tomorrow in Tbilisi and still
   * yesterday in Los Angeles, and the browser sits in neither.
   */
  it('answers the date in the business zone, not the browser one', () => {
    const late = '2026-03-10T23:00:00Z';
    expect(toBusinessDate(late, 'UTC')).toBe('2026-03-10');
    expect(toBusinessDate(late, 'Asia/Tbilisi')).toBe('2026-03-11');
    expect(toBusinessDate(late, 'America/Los_Angeles')).toBe('2026-03-10');

    const early = '2026-03-10T02:00:00Z';
    expect(toBusinessDate(early, 'America/Los_Angeles')).toBe('2026-03-09');
  });
});

describe('formatIsoDate', () => {
  /**
   * An `IsoDate` is already in the business's zone, so this converts nothing. The value of the
   * test is the date west of Greenwich: `new Date('2026-12-24')` is UTC midnight, which any
   * formatter reading it at a negative offset would render as the 23rd.
   */
  it('renders the date it was given, whatever the browser is set to', () => {
    expect(formatIsoDate('2026-12-24')).toBe('24 December 2026');
    expect(formatIsoDate('2026-01-01')).toBe('1 January 2026');
  });
});

describe('isoDateWeekday', () => {
  it('numbers Monday 1 through Sunday 7, matching the wire', () => {
    expect(isoDateWeekday('2026-03-09')).toBe(1);
    expect(isoDateWeekday('2026-03-10')).toBe(2);
    expect(isoDateWeekday('2026-03-15')).toBe(7);
  });
});

describe('addIsoDays', () => {
  it('adds calendar days, not 24-hour spans', () => {
    expect(addIsoDays('2026-03-10', 1)).toBe('2026-03-11');
    expect(addIsoDays('2026-03-01', -1)).toBe('2026-02-28');
    // Berlin gains an hour overnight on 29 March 2026; a day is still a day.
    expect(addIsoDays('2026-03-28', 1)).toBe('2026-03-29');
    expect(addIsoDays('2026-03-29', 1)).toBe('2026-03-30');
  });
});
