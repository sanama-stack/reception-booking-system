import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithToasts, serve } from '@/test/harness';
import { EMPLOYEE, NO_TIMES, SERVICE } from '@/test/fixtures';
import { AvailabilitySection } from './availability-section';

/**
 * The two empty states of the availability preview — rule 7 (docs/09-phase-plan.md §5).
 *
 * The preview is the only section on the employee page that reads rather than writes, and the four
 * editors above it all change what it computes. So its empty states have to distinguish *this
 * person cannot be booked for anything* from *you have not told me which day* — the first is a
 * configuration problem with a fix one section up, the second is a question still open.
 *
 * Its third empty state, the engine's own `emptyReason` copy, is asserted once in
 * `components/empty-reason.test.tsx` rather than three times over: the preview, the booking flow
 * and the public page all render the same explanations, and this component chooses none of them.
 */

const UNASSIGNED = { ...EMPLOYEE, serviceIds: [] };

describe('AvailabilitySection', () => {
  it('explains an unassigned person rather than computing against no service', () => {
    renderWithToasts(
      <AvailabilitySection employee={UNASSIGNED} services={[SERVICE]} timezone="UTC" />,
    );

    expect(screen.getByText('Nothing to preview yet')).toBeInTheDocument();
    expect(
      screen.getByText(/is not assigned to any service, so there is nothing to compute/),
    ).toBeVisible();

    // No question can be asked, so none of the controls that ask one are offered.
    expect(screen.queryByLabelText('Date')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Recalculate' })).not.toBeInTheDocument();
  });

  it('asks for a date rather than guessing one when the field is cleared', async () => {
    serve({ kind: 'body', bodies: { '/availability': NO_TIMES } });
    renderWithToasts(
      <AvailabilitySection employee={EMPLOYEE} services={[SERVICE]} timezone="UTC" />,
    );

    const date = screen.getByLabelText('Date');
    fireEvent.change(date, { target: { value: '' } });

    expect(await screen.findByText('Pick a date')).toBeInTheDocument();
    expect(screen.getByText('Availability is computed one day at a time.')).toBeVisible();
  });
});
