import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithToasts } from '@/test/harness';
import { TimeOffSection } from './time-off-section';

/** Rule 7's empty state for one person's time off. The page fetches; the states are the gate's. */
describe('TimeOffSection, with nothing booked', () => {
  it('says the weekly schedule is what applies until something is added', () => {
    renderWithToasts(
      <TimeOffSection
        employeeId="employee-1"
        name="Nino Beridze"
        list={{ timezone: 'UTC', timeOff: [] }}
        onChanged={() => Promise.resolve()}
      />,
    );

    expect(screen.getByText('No time off booked')).toBeInTheDocument();
    expect(screen.getByText(/Their working schedule applies every week/)).toBeVisible();
  });
});
