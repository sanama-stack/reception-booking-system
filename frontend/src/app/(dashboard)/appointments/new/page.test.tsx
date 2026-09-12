import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderScreen, serve } from '@/test/harness';
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
