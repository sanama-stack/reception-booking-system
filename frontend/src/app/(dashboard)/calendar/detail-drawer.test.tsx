import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderScreen, serve } from '@/test/harness';
import { DetailDrawer } from './detail-drawer';

/**
 * The one place in the application that does **not** get its loading and error states from
 * `ResourceGate` — docs/09-phase-plan.md §5, rule 7, and `src/test/screens/catalogue.ts`, which
 * records why.
 *
 * It hand-rolls them because both have to sit inside the dialog, under a heading and above a Close
 * button: a gate rendered around the drawer's contents would put a bare spinner in a panel with no
 * title and no way out of it. That makes this the one screen whose states could drift from every
 * other screen's without anything noticing, so they are asserted directly.
 */
describe('DetailDrawer', () => {
  it('is busy while the appointment is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<DetailDrawer appointmentId="appointment-1" onClose={() => {}} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a retry and a way out", async () => {
    serve({ kind: 'failing', detail: 'That appointment could not be read.' });
    renderScreen(<DetailDrawer appointmentId="appointment-1" onClose={() => {}} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That appointment could not be read.',
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    // The part a `ResourceGate` could not have supplied, and the reason this one is hand-rolled:
    // a failed panel that cannot be closed is a trapped screen.
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Appointment' })).toBeInTheDocument();
  });
});
