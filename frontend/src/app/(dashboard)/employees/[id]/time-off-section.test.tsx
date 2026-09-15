import { describe, expect, it } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithToasts, serve } from '@/test/harness';
import userEvent from '@testing-library/user-event';
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

/**
 * The same two-place split `closures-screen` has, on the other screen that owns dated rows.
 *
 * Adding banners — the form is still there, holding dates the owner may only need to nudge.
 * Removing toasts — the row it was about has gone from under the dialog. Neither had ever been
 * pressed here.
 */
describe('TimeOffSection, when a write is refused', () => {
  const EMPTY = { timezone: 'UTC', timeOff: [] };
  const ONE = {
    timezone: 'UTC',
    timeOff: [
      {
        id: 'timeoff-1',
        startsAt: '2026-10-05T00:00:00Z',
        endsAt: '2026-10-09T23:59:59Z',
        startDate: '2026-10-05',
        endDate: '2026-10-09',
        reason: 'Holiday',
      },
    ],
  };

  function section(list: typeof EMPTY | typeof ONE) {
    renderWithToasts(
      <TimeOffSection
        employeeId="employee-1"
        name="Nino Beridze"
        list={list}
        onChanged={() => Promise.resolve()}
      />,
    );
  }

  /** `fireEvent.change`, because jsdom leaves a `type="date"` input empty under `userEvent.type`. */
  async function addTimeOff(): Promise<void> {
    fireEvent.change(screen.getByLabelText('First day off'), { target: { value: '2026-10-05' } });
    fireEvent.change(screen.getByLabelText('Last day off'), { target: { value: '2026-10-09' } });
    await userEvent.click(screen.getByRole('button', { name: 'Add time off' }));
  }

  it('banners the server sentence when the add is refused', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'TIME_OFF_OVERLAPS',
        detail: 'That overlaps time off they already have.',
      },
    });
    section(EMPTY);

    await addTimeOff();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'That overlaps time off they already have.',
    );
  });

  it('keeps the dates so the owner can adjust rather than retype them', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    section(EMPTY);

    await addTimeOff();

    await screen.findByRole('alert');
    expect(screen.getByLabelText('First day off')).toHaveValue('2026-10-05');
    expect(screen.getByLabelText('Last day off')).toHaveValue('2026-10-09');
  });

  it('toasts the server sentence when a removal is refused', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'TIME_OFF_IN_PAST',
        detail: 'Time off that has already started cannot be removed.',
      },
    });
    section(ONE);

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }));
    await userEvent.click(screen.getByRole('button', { name: 'Remove time off' }));

    expect(
      await screen.findByText('Time off that has already started cannot be removed.'),
    ).toBeInTheDocument();
  });
});
