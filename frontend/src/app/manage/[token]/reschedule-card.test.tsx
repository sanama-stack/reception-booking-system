import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api/client';
import type { ManagedAppointment } from '@/lib/public';
import { renderScreen, serve } from '@/test/harness';
import { RescheduleCard } from './reschedule-card';

/**
 * A move that is refused, and the sentence only this card can say.
 *
 * Like the cancel dialog it delegates the refusal upward through `onFailed` — but a stale slot is
 * the one case it also answers itself, because the page's banner cannot say the thing that
 * matters here: **the appointment has not moved.** A Customer who pressed a time and got an error
 * has no way to know whether the move half-happened, and this is the screen that tells them.
 */

const APPOINTMENT: ManagedAppointment = {
  id: 'appointment-1',
  confirmationCode: '18SKVFDC',
  status: 'CONFIRMED',
  startsAt: '2026-09-21T09:00:00Z',
  endsAt: '2026-09-21T09:30:00Z',
  timezone: 'UTC',
  service: { name: 'Haircut', durationMinutes: 30 },
  employee: { fullName: 'Nino Beridze' },
  price: { amount: '40.00', currency: 'GEL' },
  note: null,
  canCancel: true,
  canReschedule: true,
  emailOnFile: false,
  business: {
    name: 'Aria Studio',
    phone: null,
    email: null,
    timezone: 'UTC',
    cancellationPolicy: null,
  },
};

const BODIES = {
  '/public/appointments/manage/availability': {
    timezone: 'UTC',
    days: [
      {
        date: '2026-09-22',
        slots: [
          {
            startsAt: '2026-09-22T11:00:00Z',
            endsAt: '2026-09-22T11:30:00Z',
            employee: { id: 'employee-1', fullName: 'Nino Beridze' },
          },
        ],
      },
    ],
    emptyReason: null,
  },
};

function card(onFailed: (cause: ApiError) => void) {
  renderScreen(
    <RescheduleCard
      token="manage-token"
      appointment={APPOINTMENT}
      today="2026-09-21"
      onMoved={vi.fn()}
      onFailed={onFailed}
      onDone={vi.fn()}
    />,
  );
}

/** Picks the one time on offer and presses Move. */
async function pickATimeAndMove(): Promise<void> {
  await userEvent.click(await screen.findByRole('button', { name: '11:00' }));
  await userEvent.click(screen.getByRole('button', { name: /^move/i }));
}

describe('the manage page reschedule card, refused', () => {
  it('hands the page the server refusal rather than swallowing it', async () => {
    const onFailed = vi.fn();
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'VERSION_CONFLICT',
        detail: 'This appointment was changed by someone else.',
      },
    });
    card(onFailed);

    await pickATimeAndMove();

    await vi.waitFor(() => expect(onFailed).toHaveBeenCalledTimes(1));
    const cause = onFailed.mock.calls[0]![0] as ApiError;
    expect(cause).toBeInstanceOf(ApiError);
    expect(cause.code).toBe('VERSION_CONFLICT');
  });

  /**
   * The sentence the page cannot say. A refusal above the summary tells the Customer the move
   * failed; only this card knows the appointment is still sitting at its original time, and that
   * is the half that stops somebody booking a second one.
   */
  it('says the appointment has not moved when the time has gone', async () => {
    const onFailed = vi.fn();
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SLOT_UNAVAILABLE',
        detail: 'That time has just been taken.',
      },
    });
    card(onFailed);

    await pickATimeAndMove();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/has not moved/i);
    expect(alert).toHaveTextContent(/still at its original time/i);
    expect(onFailed).toHaveBeenCalledTimes(1);
  });

  /** A refusal that is not about the slot leaves the grid alone — no banner, no re-ask. */
  it('does not claim a recalculation when the refusal was about something else', async () => {
    const onFailed = vi.fn();
    serve({
      kind: 'body',
      bodies: BODIES,
      refusing: { kind: 'failing', status: 500, code: 'INTERNAL_ERROR' },
    });
    card(onFailed);

    await pickATimeAndMove();

    await vi.waitFor(() => expect(onFailed).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('still hands up an ApiError when the reply cannot be read', async () => {
    const onFailed = vi.fn();
    serve({ kind: 'body', bodies: BODIES, refusing: { kind: 'unreadable' } });
    card(onFailed);

    await pickATimeAndMove();

    await vi.waitFor(() => expect(onFailed).toHaveBeenCalledTimes(1));
    expect(onFailed.mock.calls[0]![0]).toBeInstanceOf(ApiError);
  });
});
