/**
 * The wire shape of `GET /analytics/summary`, mirroring `AnalyticsResponses` on the server
 * (docs/04-api-overview.md §5).
 *
 * Three things here are easy to render wrongly, and each is called out where it is declared: a rate
 * that is `null` rather than `0`, a revenue figure that counts one status in one currency, and a
 * set of period counts that are **not** bounded by the range that was asked for.
 */

import type { IsoDate, Timezone } from '@/lib/time';

/** Both dates inclusive, and the zone every boundary behind them was resolved in. */
export interface AnalyticsRange {
  from: IsoDate;
  to: IsoDate;
  timezone: Timezone;
}

export interface AnalyticsCounts {
  confirmed: number;
  completed: number;
  cancelled: number;
  noShow: number;
  total: number;
}

/**
 * Today, this week and this month — **as of now, not as of the range**.
 *
 * Deliberate, and the server says so: bounded by the range, a report on last September would answer
 * "today: 0" for every business on earth. These are the dashboard's live numbers travelling with the
 * report, so a screen must label them as now rather than letting them read as part of the range.
 *
 * The week starts on Monday, ISO-8601, for every viewer — not the browser's locale, which would make
 * one business's numbers differ by who is looking at them.
 */
export interface AnalyticsPeriods {
  today: number;
  thisWeek: number;
  thisMonth: number;
}

/**
 * What was actually earned, from the price snapshotted at booking.
 *
 * `basis` is a constant the server sends anyway, because "does this include the ones that were
 * cancelled?" is the first question anyone asks of a revenue figure, and the answer belongs beside
 * the number rather than in prose on a screen that can drift from it.
 *
 * **`currency` is the Business's current currency, and only appointments priced in it are counted.**
 * A business that switched currency has older revenue this figure does not report. It is never
 * wrong about what it claims — the figure stated in GEL really is the GEL revenue — but it is not
 * the whole truth, and `excluded` is where the rest of the truth now goes. The screen names the
 * remainder rather than letting a smaller number read as a bad month.
 */
export interface AnalyticsRevenue {
  amount: string;
  currency: string;
  basis: 'COMPLETED_ONLY';
  /**
   * Every other currency found among the same completed appointments, each with its own sum.
   *
   * **Empty, not `null`, when there is no remainder** — unlike `rates`, which is null because it has
   * no meaningful zero. "Nothing was excluded" is a fact and has one, so a screen renders this by
   * its length and never has to branch on absence first.
   *
   * **Never add these to `amount`, or to each other.** Adding lari to dollars produces a number that
   * is not money; that refusal is the entire reason the field is a list rather than a larger figure
   * above it (ADR-0010).
   */
  excluded: AnalyticsExcludedRevenue[];
}

/** One currency `AnalyticsRevenue.amount` does not cover, and what was taken in it. */
export interface AnalyticsExcludedRevenue {
  currency: string;
  amount: string;
}

/**
 * Fractions, not percentages: `0.073` is 7.3%, to a tenth of a percent.
 *
 * **`null` means there were no appointments to divide by**, and it is not zero. "0% cancellation"
 * from an empty month is a lie an owner will act on, so a screen renders nothing there rather than
 * a number.
 */
export interface AnalyticsRates {
  cancellation: number | null;
  noShow: number | null;
}

export interface TopService {
  serviceId: string;
  name: string;
  count: number;
}

export interface AnalyticsSummary {
  range: AnalyticsRange;
  counts: AnalyticsCounts;
  periods: AnalyticsPeriods;
  revenue: AnalyticsRevenue;
  rates: AnalyticsRates;
  /** At most five, already ordered. A deactivated service that was booked keeps its name here. */
  topServices: TopService[];
}
