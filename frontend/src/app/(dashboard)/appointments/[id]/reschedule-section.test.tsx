import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithToasts, serve } from '@/test/harness';
import { RescheduleSection } from './reschedule-section';
import { APPOINTMENT, EMPLOYEES, NO_TIMES, SERVICE } from '@/test/fixtures';

/**
 * The two empty states of the move panel — rule 7 (docs/09-phase-plan.md §5).
 *
 * They are not variations on each other. *No service* is a dead end: availability is computed from
 * the service's length, so with the service deleted there is nothing to offer and the copy has to
 * say what to do instead. *No date* is a question not yet answered, and the panel is one field away
 * from working. A screen that showed either message in the other's situation would be wrong in a
 * way no spinner or error state would reveal.
 *
 * The section takes its data as props and reads only availability, so these render without a
 * session — but `useToast` is real, which is why the toast provider is still here.
 */

function moveFor(service: typeof SERVICE | null) {
  return (
    <RescheduleSection
      appointment={APPOINTMENT.appointment}
      service={service}
      employees={EMPLOYEES}
      timezone="UTC"
      onUpdated={() => {}}
      onReload={() => Promise.resolve()}
      onDone={() => {}}
    />
  );
}

describe('RescheduleSection', () => {
  it('refuses to offer times for a service that has been deleted, and says where to go instead', () => {
    serve({ kind: 'body', bodies: { '/availability': NO_TIMES } });
    renderWithToasts(moveFor(null));

    expect(screen.getByText('This service no longer exists')).toBeInTheDocument();
    expect(screen.getByText(/Cancel and rebook under a service you still provide/)).toBeVisible();

    // The dead end is the whole state: offering a date field beside that message would invite the
    // owner to answer a question this panel has already said it cannot answer.
    expect(screen.queryByLabelText('New date')).not.toBeInTheDocument();
  });

  it('asks for a date rather than guessing one when the field is cleared', async () => {
    serve({ kind: 'body', bodies: { '/availability': NO_TIMES } });
    renderWithToasts(moveFor(SERVICE));

    // It opens on the appointment's own day, so the empty-date state has to be reached rather than
    // rendered — which is also the only way an owner reaches it.
    const date = screen.getByLabelText('New date');
    expect(date).toHaveValue('2026-09-20');

    fireEvent.change(date, { target: { value: '' } });

    expect(await screen.findByText('Pick a date')).toBeInTheDocument();
    expect(screen.getByText('Times are computed one day at a time.')).toBeVisible();
  });
});

/**
 * The move refused, and the branch that leaves the panel rather than staying in it.
 *
 * Two dispositions, and the difference is about whether this screen can still be trusted.
 * An ordinary refusal keeps the panel open, banners the server's sentence, drops the chosen time
 * and re-asks for the times — because the grid was computed against a world that has since
 * moved, most obviously somebody taking the slot first, and leaving a dead list up invites a
 * second press on a time that is already gone.
 *
 * `VERSION_CONFLICT` is different: the appointment itself changed under this screen, so there is
 * nothing here worth re-asking. It reloads and closes.
 */

const TIMES = {
  timezone: 'UTC',
  days: [
    {
      date: '2026-09-22',
      slots: [
        {
          startsAt: '2026-09-22T10:00:00Z',
          endsAt: '2026-09-22T10:45:00Z',
          employee: { id: 'employee-1', fullName: 'Nino Beridze' },
        },
      ],
    },
  ],
  emptyReason: null,
};

function movePanel(onReload = vi.fn(() => Promise.resolve()), onDone = vi.fn()) {
  renderWithToasts(
    <RescheduleSection
      appointment={APPOINTMENT.appointment}
      service={SERVICE}
      employees={EMPLOYEES}
      timezone="UTC"
      onUpdated={vi.fn()}
      onReload={onReload}
      onDone={onDone}
    />,
  );
  return { onReload, onDone };
}

async function pickATimeAndMove(): Promise<void> {
  fireEvent.change(screen.getByLabelText('New date'), { target: { value: '2026-09-22' } });
  await userEvent.click(await screen.findByRole('button', { name: '10:00' }));
  await userEvent.click(screen.getByRole('button', { name: 'Move appointment' }));
}

describe('RescheduleSection, refused', () => {
  it('banners the server sentence and drops the time that has gone', async () => {
    serve({
      kind: 'body',
      bodies: { '/availability': TIMES },
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SLOT_UNAVAILABLE',
        detail: 'That time has just been taken.',
      },
    });
    const { onDone } = movePanel();

    await pickATimeAndMove();

    expect(await screen.findByRole('alert')).toHaveTextContent('That time has just been taken.');
    // The choice is dropped, so the same doomed press cannot be repeated.
    expect(screen.getByRole('button', { name: 'Move appointment' })).toBeDisabled();
    // And the panel stays, because the owner still wants to move this appointment.
    expect(onDone).not.toHaveBeenCalled();
  });

  /**
   * The exception. The appointment changed underneath, so re-asking for times on this screen
   * would be answering a question about a booking that no longer looks like this one.
   */
  it('reloads and closes on a version conflict instead of re-asking', async () => {
    serve({
      kind: 'body',
      bodies: { '/availability': TIMES },
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'VERSION_CONFLICT',
        detail: 'This appointment was changed by someone else.',
      },
    });
    const { onReload, onDone } = movePanel();

    await pickATimeAndMove();

    await waitFor(() => expect(onReload).toHaveBeenCalledTimes(1));
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
