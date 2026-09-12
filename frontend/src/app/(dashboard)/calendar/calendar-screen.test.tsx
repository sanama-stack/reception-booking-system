import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import type { Calendar } from '@/lib/calendar';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { CalendarScreen } from './calendar-screen';
import type { Span } from './geometry';

/**
 * All three states of the calendar — docs/09-phase-plan.md §5, rule 7.
 *
 * Its empty state is not an empty *list*: the calendar has nothing to draw when there is nobody to
 * draw a column for, which is a different situation from a quiet day and gets a different sentence
 * and a different way out.
 */

const DATE = '2026-03-10';

const EMPTY_CALENDAR: Calendar = {
  range: { from: DATE, to: DATE, timezone: SESSION.business.timezone },
  appointments: [],
  closures: [],
  timeOff: [],
};

function props(over: Partial<Parameters<typeof CalendarScreen>[0]> = {}) {
  return {
    path: '/calendar',
    view: 'day' as const,
    date: DATE,
    employees: [],
    openingHours: new Map<number, Span[]>(),
    ...over,
  };
}

describe('CalendarScreen', () => {
  it('is busy while the week is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<CalendarScreen {...props()} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: 'The calendar could not be read.' });
    renderScreen(<CalendarScreen {...props()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('The calendar could not be read.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('says there is nobody to draw a column for, and offers to add one', async () => {
    serve({ kind: 'body', bodies: { '/calendar': EMPTY_CALENDAR } });
    renderScreen(<CalendarScreen {...props()} />);

    expect(await screen.findByText('No one to show')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add an employee' })).toHaveAttribute(
      'href',
      '/employees/new',
    );
  });
});
