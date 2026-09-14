import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api/client';
import type { ManagedAppointment } from '@/lib/public';
import { renderScreen, serve } from '@/test/harness';
import { CancelDialog } from './cancel-dialog';

/**
 * A cancellation that is refused, and the one thing this dialog promises about it.
 *
 * It renders nothing of its own when the write fails — `manage/page.tsx` shows the refusal above
 * the summary — so what is asserted here is the handoff: `onFailed` is always reached, and always
 * with an `ApiError`. That is the whole contract, and it is worth a test because the dialog's own
 * comment calls the alternative "the worst of the three outcomes: the Customer walks away
 * believing it did".
 *
 * The non-`ApiError` branch is the interesting one. Its comment says nothing reaches it through
 * the client — true of the fetch, and false of the parse until last sitting's fix. Unlike the two
 * auth forms, this component answered that branch with a *built* `ApiError` rather than `null`,
 * so it was never silent; the assertion keeps it that way.
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

function openDialog(onFailed: (cause: ApiError) => void) {
  renderScreen(
    <CancelDialog
      open
      token="manage-token"
      appointment={APPOINTMENT}
      onCancelled={vi.fn()}
      onFailed={onFailed}
      onDismiss={vi.fn()}
    />,
  );
}

async function confirm(): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: 'Cancel appointment' }));
}

describe('the manage page cancel dialog, refused', () => {
  it('hands the page the server refusal rather than swallowing it', async () => {
    const onFailed = vi.fn();
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'CANCELLATION_WINDOW_CLOSED',
        detail: 'This appointment can no longer be cancelled online.',
      },
    });
    openDialog(onFailed);

    await confirm();

    await vi.waitFor(() => expect(onFailed).toHaveBeenCalledTimes(1));
    const cause = onFailed.mock.calls[0]![0] as ApiError;
    expect(cause).toBeInstanceOf(ApiError);
    expect(cause.code).toBe('CANCELLATION_WINDOW_CLOSED');
    expect(cause.message).toBe('This appointment can no longer be cancelled online.');
  });

  /**
   * The branch the comment calls unreachable. It is reachable through a `2xx` whose body is not
   * JSON, and what matters is that the page is still handed an `ApiError` — the type every caller
   * of `onFailed` branches on.
   */
  it('still hands up an ApiError when the reply cannot be read', async () => {
    const onFailed = vi.fn();
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'unreadable' } });
    openDialog(onFailed);

    await confirm();

    await vi.waitFor(() => expect(onFailed).toHaveBeenCalledTimes(1));
    expect(onFailed.mock.calls[0]![0]).toBeInstanceOf(ApiError);
  });

  /**
   * The `finally`. A dialog left busy cannot be confirmed again and cannot be dismissed — its own
   * `onCancel` returns early while `busy` — so a refusal that did not release it would strand the
   * Customer on a modal with two dead buttons.
   */
  it('releases the dialog so it can be dismissed after a refusal', async () => {
    const onFailed = vi.fn();
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    openDialog(onFailed);

    await confirm();

    await vi.waitFor(() => expect(onFailed).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('button', { name: 'Keep it' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Cancel appointment' })).toBeEnabled();
  });
});
