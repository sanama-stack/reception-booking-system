import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Calendar } from '@/lib/calendar';
import type { EmployeeDetail } from '@/lib/staff';
import { minutesOfDay } from '@/lib/time';
import { DayView } from './day-view';
import type { Span } from './geometry';

/**
 * The business-timezone requirement, drawn — ADR-0003, and the phase-11 row that says this must be
 * proven *against its counterfactual*.
 *
 * The calendar is where a zone mistake is worst, because the zone decides pixels and not only
 * text: `minutesOfDay` is a block's top edge, so a block read in the browser's zone sits in the
 * wrong row of a grid that otherwise looks perfectly correct. Both halves are asserted here — the
 * label, and the offset.
 *
 * The suite runs at `Asia/Tbilisi`, +04:00 (`vitest.config.mts`), and this business is at UTC. The
 * gap is four hours, which is 240 px of grid.
 */

const TIMEZONE = 'UTC';
const DATE = '2026-03-10';
const STARTS_AT = '2026-03-10T09:00:00Z';
const ENDS_AT = '2026-03-10T09:45:00Z';

/** 08:00–18:00, so the window starts at 08:00 and 09:00 is 60 px down rather than at the top. */
const OPENING_HOURS: Span[] = [{ startMinute: 8 * 60, endMinute: 18 * 60 }];

const EMPLOYEE: EmployeeDetail = {
  id: 'employee-1',
  fullName: 'Nino Beridze',
  email: null,
  phone: null,
  jobTitle: null,
  active: true,
  serviceIds: ['service-1'],
  updatedAt: STARTS_AT,
};

const CALENDAR: Calendar = {
  range: { from: DATE, to: DATE, timezone: TIMEZONE },
  appointments: [
    {
      id: 'appointment-1',
      startsAt: STARTS_AT,
      endsAt: ENDS_AT,
      status: 'CONFIRMED',
      service: { id: 'service-1', name: 'Cut and finish' },
      employee: { id: EMPLOYEE.id, name: EMPLOYEE.fullName },
      customerName: 'Clara Classic',
      source: 'CLASSIC',
    },
  ],
  closures: [],
  timeOff: [],
};

function renderDay() {
  render(
    <DayView
      date={DATE}
      calendar={CALENDAR}
      employees={[EMPLOYEE]}
      openingHours={OPENING_HOURS}
      // A different day, so the current-time line is not drawn and cannot be mistaken for a block.
      now={new Date('2026-03-09T12:00:00Z')}
      onOpen={() => {}}
      onPick={() => {}}
    />,
  );
}

describe('the counterfactual this test rests on', () => {
  /**
   * If the browser and the business agreed, every assertion below would pass against a component
   * that ignored the timezone entirely. This is what makes them mean something — and what goes
   * red, loudly, if `TZ` ever stops reaching the worker.
   */
  it('has the browser four hours ahead of the business', () => {
    expect(new Date(STARTS_AT).getHours(), 'the ambient zone is not +04:00').toBe(13);
    expect(minutesOfDay(STARTS_AT, TIMEZONE)).toBe(9 * 60);
    expect(minutesOfDay(STARTS_AT, 'Asia/Tbilisi')).toBe(13 * 60);
  });
});

describe('DayView, for a UTC business seen from a browser at +04:00', () => {
  it('labels the block 09:00, not 13:00', () => {
    renderDay();

    expect(screen.getByRole('button', { name: /09:00 – 09:45/ })).toBeInTheDocument();

    // Scoped to the blocks, not to the page. `13:00` is on this screen legitimately — it is an
    // hour label in the gutter, because the window runs to 18:00 — and a bare
    // `queryByText(/13:00/)` finds it and fails against a correct render. T49, from the other
    // side: a locator that names the wrong thing is as wrong when it matches as when it misses.
    expect(screen.queryByRole('button', { name: /13:00/ })).not.toBeInTheDocument();
  });

  /**
   * The half a label cannot show. One pixel is one minute (`time-grid.tsx`), the window starts at
   * 08:00, so 09:00 is 60 px down. The browser's reading of the same instant is 13:00, which is
   * 300 px — five rows lower, on a grid whose hour labels would still read correctly.
   */
  it('draws the block 60 px down, where 09:00 is, and not at 300 px', () => {
    renderDay();

    const block = screen.getByRole('button', { name: /09:00 – 09:45/ });
    const positioned = block.parentElement;

    expect(positioned).not.toBeNull();
    expect(positioned).toHaveStyle({ top: '60px', height: '45px' });
    expect(positioned, 'drawn where the browser thinks 09:00Z is').not.toHaveStyle({
      top: '300px',
    });
  });

  /** The gutter is the other thing a reader checks the block against. */
  it('labels the hour rows from the business day, 08:00 at the top', () => {
    renderDay();

    expect(screen.getByText('08:00')).toBeInTheDocument();
    expect(screen.getByText('18:00')).toBeInTheDocument();
    expect(screen.queryByText('22:00')).not.toBeInTheDocument();
  });
});
