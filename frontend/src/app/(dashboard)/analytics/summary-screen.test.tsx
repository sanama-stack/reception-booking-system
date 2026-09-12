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
  revenue: { amount: '0.00', currency: SESSION.business.currency, basis: 'COMPLETED_ONLY' },
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
});
