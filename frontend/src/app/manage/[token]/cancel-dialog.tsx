'use client';

import { useState } from 'react';
import { ConfirmDialog, Textarea } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { publicApi, type ManagedAppointment } from '@/lib/public';
import { formatDate, formatTime } from '@/lib/time';

/**
 * Cancelling, behind a confirmation.
 *
 * Terminal, and terminal in a way an accidental tap cannot undo: the state machine has no edge out
 * of `CANCELLED`, the slot goes back on sale immediately, and a Customer who wanted it back would
 * have to race everybody else for it. So it is confirmed first — the same treatment the dashboard
 * gives the same operation, for the same reason.
 *
 * **Idempotent on the server**, which is what makes a double tap or a timed-out request safe: the
 * second call returns the same appointment and sends no second email. That is worth knowing here
 * because it is the reason this dialog does not need to defend against one.
 *
 * The dialog restates when the appointment is, because a Customer may have several and arrived
 * from a link they cannot see the contents of. Confirming a cancellation without being shown which
 * one is being cancelled is the shape of mistake this whole dialog exists to prevent.
 */
export function CancelDialog({
  open,
  token,
  appointment,
  onCancelled,
  onFailed,
  onDismiss,
}: {
  open: boolean;
  token: string;
  appointment: ManagedAppointment;
  onCancelled: (next: ManagedAppointment) => void | Promise<void>;
  onFailed: (cause: ApiError) => void | Promise<void>;
  onDismiss: () => void;
}) {
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);

  async function onConfirm() {
    setBusy(true);
    try {
      const next = await publicApi.cancel(appointment.id, {
        // The token, never the id, is what authorises this. The id in the path is a claim the
        // server checks against whatever the proof resolves — so both come from the appointment
        // this page resolved, and cannot name two different bookings.
        authority: { manageToken: token },
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      });
      setReason('');
      await onCancelled(next);
    } catch (cause) {
      await onFailed(
        cause instanceof ApiError
          ? cause
          : // Unreachable through the client, which wraps even a dead connection as a
            // `NETWORK_ERROR`. Answered anyway, because a cancellation that neither happens nor
            // complains is the worst of the three outcomes: the Customer walks away believing it
            // did.
            new ApiError({
              code: 'INTERNAL_ERROR',
              message: 'The appointment could not be cancelled. Please try again.',
              status: 0,
            }),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <ConfirmDialog
      open={open}
      title="Cancel this appointment?"
      confirmLabel="Cancel appointment"
      cancelLabel="Keep it"
      tone="danger"
      busy={busy}
      onConfirm={() => void onConfirm()}
      onCancel={() => {
        if (busy) return;
        setReason('');
        onDismiss();
      }}
    >
      <p className="text-ink font-medium">
        {appointment.service.name} with {appointment.employee.fullName}
      </p>
      <p>
        {formatDate(appointment.startsAt, appointment.timezone)} at{' '}
        <span className="tabular-nums">
          {formatTime(appointment.startsAt, appointment.timezone)}
        </span>
      </p>
      <p>
        The time goes back on sale straight away, so booking it again later may not be possible. If
        you only need a different time, moving the appointment keeps it.
      </p>
      <Textarea
        label="Reason"
        value={reason}
        maxLength={500}
        rows={2}
        onChange={(event) => setReason(event.target.value)}
        hint="Optional, and passed on to the business."
      />
    </ConfirmDialog>
  );
}
