import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderScreen, serve } from '@/test/harness';
import userEvent from '@testing-library/user-event';
import NewAppointmentPage from './page';
import { EMPLOYEES, NO_TIMES, SERVICE } from '@/test/fixtures';

/**
 * The two empty states of booking from the dashboard — rule 7 (docs/09-phase-plan.md §5).
 *
 * *No services* is the one worth being careful about. An appointment is a service performed by a
 * person at a time, so a business with no services has nothing this screen can offer — and the
 * state that says so carries the only useful next step, which is a link to the form that fixes it.
 * Rendering a booking form with an empty dropdown instead would be a screen that looks usable and
 * cannot complete.
 *
 * The screen reads both the catalogue and the staff list before it can decide, so both are served
 * in every case here: an unanswered one shows the error state, which is not what is under test.
 */

describe('NewAppointmentPage', () => {
  it('sends an owner with no services to the form that fixes that, rather than an empty picker', async () => {
    serve({
      kind: 'body',
      bodies: { '/services': { services: [] }, '/employees': { employees: [] } },
    });
    renderScreen(<NewAppointmentPage />);

    expect(await screen.findByText('No services to book')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add a service' })).toHaveAttribute(
      'href',
      '/services/new',
    );

    // Nothing to book means nothing to book *with*. A form rendered under that message would be a
    // screen contradicting itself.
    expect(screen.queryByRole('button', { name: 'Book appointment' })).not.toBeInTheDocument();
  });

  it('asks for a date rather than guessing one when the field is cleared', async () => {
    serve({
      kind: 'body',
      bodies: {
        '/services': { services: [SERVICE] },
        '/employees': EMPLOYEES,
        '/availability': NO_TIMES,
      },
    });
    renderScreen(<NewAppointmentPage />);

    // It opens on today in the *business's* zone, never the browser's (ADR-0003), so this state is
    // reached by clearing the field — the only way an owner reaches it too.
    const date = await screen.findByLabelText('Date');
    fireEvent.change(date, { target: { value: '' } });

    expect(await screen.findByText('Pick a date')).toBeInTheDocument();
    expect(screen.getByText('Times are computed one day at a time.')).toBeVisible();
  });
});

/**
 * The owner's own booking form refused, and the promise it shares with the public one.
 *
 * A stale-slot refusal drops the chosen time and re-asks for the times, because the grid was
 * computed against a world that has moved — **and everything typed stays**. That last clause is
 * the one a reader can check, and the one a screen could quietly break while still showing the
 * right sentence.
 */
describe('NewAppointmentPage, when the booking is refused', () => {
  const TIMES = {
    timezone: 'UTC',
    days: [
      {
        date: '2026-09-20',
        slots: [
          {
            startsAt: '2026-09-20T09:00:00Z',
            endsAt: '2026-09-20T09:45:00Z',
            employee: { id: 'employee-1', fullName: 'Nino Beridze' },
          },
        ],
      },
    ],
    emptyReason: null,
  };

  function servePage(refusing: Record<string, unknown>) {
    serve({
      kind: 'body',
      bodies: {
        '/services': { services: [SERVICE] },
        '/employees': EMPLOYEES,
        '/availability': TIMES,
      },
      refusing: refusing as never,
    });
  }

  async function bookIt(): Promise<void> {
    fireEvent.change(await screen.findByLabelText('Date'), { target: { value: '2026-09-20' } });
    // The name is '09:00Nino Beridze': this picker always labels the person, unlike the
    // fortnight grid, which names them only when the times on screen belong to several.
    await userEvent.click(await screen.findByRole('button', { name: /09:00/ }));
    await userEvent.type(screen.getByLabelText('Full name'), 'Clara Classic');
    await userEvent.type(screen.getByLabelText('Phone'), '+995555000222');
    await userEvent.click(screen.getByRole('button', { name: 'Book appointment' }));
  }

  it('shows the server sentence rather than one of its own', async () => {
    servePage({
      kind: 'failing',
      status: 422,
      code: 'OUTSIDE_BOOKING_WINDOW',
      detail: 'That time is outside the booking window.',
    });
    renderScreen(<NewAppointmentPage />);

    await bookIt();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That time is outside the booking window.',
    );
  });

  it('keeps the customer details when the chosen time has gone', async () => {
    servePage({
      kind: 'failing',
      status: 409,
      code: 'SLOT_UNAVAILABLE',
      detail: 'That time has just been taken.',
    });
    renderScreen(<NewAppointmentPage />);

    await bookIt();

    await screen.findByRole('alert');
    // The claim the owner can check: what they typed is still there to book again with.
    expect(screen.getByLabelText('Full name')).toHaveValue('Clara Classic');
    expect(screen.getByLabelText('Phone')).toHaveValue('+995555000222');
  });
});
