import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import type { AnalyticsSummary } from '@/lib/analytics';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { SummaryScreen } from './summary-screen';

/**
 * All three states of the analytics screen — docs/09-phase-plan.md §5, rule 7.
 *
 * Its two empty states are the interesting part, and the second one says so itself: a range with
 * nothing in it produces *both*, and the top-services copy exists to say it is the same emptiness
 * rather than a second thing to worry about.
 */

const EMPTY_SUMMARY: AnalyticsSummary = {
  range: { from: '2026-03-01', to: '2026-03-31', timezone: SESSION.business.timezone },
  counts: { confirmed: 0, completed: 0, cancelled: 0, noShow: 0, total: 0 },
  periods: { today: 0, thisWeek: 0, thisMonth: 0 },
  revenue: {
    amount: '0.00',
    currency: SESSION.business.currency,
    basis: 'COMPLETED_ONLY',
    excluded: [],
  },
  rates: { cancellation: null, noShow: null },
  topServices: [],
};

describe('SummaryScreen', () => {
  it('is busy while the summary is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<SummaryScreen path="/analytics/summary" />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: 'The summary could not be computed.' });
    renderScreen(<SummaryScreen path="/analytics/summary" />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The summary could not be computed.',
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('explains an empty range in both places, and says they are one emptiness', async () => {
    serve({ kind: 'body', bodies: { '/analytics/summary': EMPTY_SUMMARY } });
    renderScreen(<SummaryScreen path="/analytics/summary" />);

    expect(await screen.findByText('Nothing in this range')).toBeInTheDocument();
    expect(screen.getByText('No services booked')).toBeInTheDocument();
    expect(screen.getByText(/not a second problem/)).toBeVisible();
  });

  /**
   * The remainder, ADR-0010, on the screen rather than only in the payload.
   *
   * The figure is asserted rather than the presence of a footnote: "some revenue is excluded" is
   * the caveat this screen already had and an owner cannot act on it. `82.50 USD` is the thing
   * that tells them whether it matters, and a render that drops the amounts still shows a note.
   */
  it('names the remainder beside revenue after a currency change, with its amount', async () => {
    serve({
      kind: 'body',
      bodies: {
        '/analytics/summary': {
          ...EMPTY_SUMMARY,
          revenue: {
            amount: '340.00',
            currency: 'GEL',
            basis: 'COMPLETED_ONLY',
            excluded: [
              { currency: 'USD', amount: '82.50' },
              { currency: 'EUR', amount: '19.00' },
            ],
          },
        },
      },
    });
    renderScreen(<SummaryScreen path="/analytics/summary" />);

    const note = await screen.findByText(/Not counted above/);
    expect(note).toHaveTextContent('Completed appointments only, priced in GEL');
    expect(note).toHaveTextContent('82.50');
    expect(note).toHaveTextContent('19.00');
    // Listed, never summed: 101.50 is the number a screen that added them would show, and it is
    // not money. Nor is 441.50, which is what adding them to the figure above would produce.
    expect(note).not.toHaveTextContent('101.50');
    expect(note).not.toHaveTextContent('441.50');
  });

  /**
   * The common case, which is every business that has never changed currency.
   *
   * Asserted because the branch has a failure mode that no other test can see: a footnote rendered
   * unconditionally reads "Not counted above:" with nothing after it, which is worse than silence —
   * it tells an owner something is missing and then declines to say what.
   */
  it('says nothing about a remainder when there is none', async () => {
    serve({ kind: 'body', bodies: { '/analytics/summary': EMPTY_SUMMARY } });
    renderScreen(<SummaryScreen path="/analytics/summary" />);

    expect(
      await screen.findByText(
        `Completed appointments only, priced in ${SESSION.business.currency}`,
      ),
    ).toBeVisible();
    expect(screen.queryByText(/Not counted above/)).not.toBeInTheDocument();
  });
});
