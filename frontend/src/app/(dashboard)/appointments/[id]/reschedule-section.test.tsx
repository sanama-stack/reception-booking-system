import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
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
