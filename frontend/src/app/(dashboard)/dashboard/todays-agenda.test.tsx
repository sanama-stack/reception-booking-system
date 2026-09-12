import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import type { Calendar } from '@/lib/calendar';
import { toBusinessDate } from '@/lib/time';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { TodaysAgenda } from './todays-agenda';

/**
 * All three states of the dashboard's agenda card — docs/09-phase-plan.md §5, rule 7.
 *
 * The date is derived here the same way the component derives it — `toBusinessDate(new Date(),
 * …)` — rather than pinned with a fake clock. The component takes `now` at mount and this card is
 * the one screen whose question is literally "what time is it"; a frozen clock would make the test
 * agree with a component that had stopped asking.
 */

const TIMEZONE = SESSION.business.timezone;

function emptyCalendar(): Calendar {
  const today = toBusinessDate(new Date(), TIMEZONE);
  return {
    range: { from: today, to: today, timezone: TIMEZONE },
    appointments: [],
    closures: [],
    timeOff: [],
  };
}

describe('TodaysAgenda', () => {
  it('is busy while the week is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<TodaysAgenda timezone={TIMEZONE} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: "Today's appointments could not be read." });
    renderScreen(<TodaysAgenda timezone={TIMEZONE} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Today's appointments could not be read.",
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  /**
   * The distinction this card exists to draw: a quiet afternoon and a quiet week are not the same
   * news, and the lookahead is what lets it say which one this is.
   */
  it('says nothing is booked today, and that the week beyond is empty too', async () => {
    serve({ kind: 'body', bodies: { '/calendar': emptyCalendar() } });
    renderScreen(<TodaysAgenda timezone={TIMEZONE} />);

    expect(await screen.findByText('Nothing booked today')).toBeInTheDocument();
    expect(screen.getByText(/Nothing in the next 7 days either/)).toBeVisible();
  });
});
