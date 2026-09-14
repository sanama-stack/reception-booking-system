import userEvent from '@testing-library/user-event';
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { APPOINTMENT } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { AppointmentActions } from './appointment-actions';

/**
 * A screen whose rule has a documented exception, and the exception is the interesting half.
 *
 * Every refusal here toasts the server's own sentence and leaves the dialog open, so the owner
 * can read it and decide. **`VERSION_CONFLICT` does the opposite of all three**: it replaces the
 * server's message with one of the screen's own, closes the dialog, and re-reads the
 * appointment — because somebody else changed it since this screen loaded, so what is on screen
 * is no longer true and a second attempt would be a guess made from stale data.
 *
 * Substituting a message is normally the defect this suite hunts. Here it is correct, and the
 * two cases are asserted side by side so the difference cannot be flattened by someone
 * "simplifying" the branch away.
 */

function actions(onReload = vi.fn(() => Promise.resolve())) {
  renderWithToasts(
    <AppointmentActions
      appointment={APPOINTMENT.appointment}
      onUpdated={vi.fn()}
      onReload={onReload}
    />,
  );
  return onReload;
}

/**
 * The dialog's confirm button carries the same label as the one that opened it, so the second
 * click has to be the second match rather than a fresh query.
 */
async function markCompleted(): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: 'Mark completed' }));
  const [, confirm] = screen.getAllByRole('button', { name: 'Mark completed' });
  await userEvent.click(confirm!);
}

describe('AppointmentActions, refused', () => {
  it('toasts the server sentence for an ordinary refusal', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'APPOINTMENT_ALREADY_STARTED',
        detail: 'This appointment has not finished yet.',
      },
    });
    const onReload = actions();

    await markCompleted();

    expect(await screen.findByText('This appointment has not finished yet.')).toBeInTheDocument();
    // Not a conflict, so the screen is not stale and there is nothing to re-read.
    expect(onReload).not.toHaveBeenCalled();
  });

  /**
   * The exception. The server's own `VERSION_CONFLICT` message is about optimistic locking and
   * means nothing to an owner; what they need to know is that somebody else got there first and
   * the screen is about to correct itself.
   */
  it('re-reads the appointment on a version conflict instead of repeating the server', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'VERSION_CONFLICT',
        detail: 'The resource was modified by another request.',
      },
    });
    const onReload = actions();

    await markCompleted();

    expect(
      await screen.findByText('Somebody else changed this appointment. Reloading it.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(onReload).toHaveBeenCalledTimes(1));
    // The server's own wording is about locking and is deliberately not shown.
    expect(
      screen.queryByText('The resource was modified by another request.'),
    ).not.toBeInTheDocument();
  });
});
