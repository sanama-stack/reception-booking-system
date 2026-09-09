'use client';

import { useState } from 'react';
import { Button, ConfirmDialog, Textarea, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import {
  appointmentApi,
  type AppointmentDetail,
  type AppointmentWithHistory,
  type ChangeableStatus,
} from '@/lib/appointments';

/**
 * Cancel, complete and no-show — the three things that can be done to a confirmed appointment
 * without changing when it is.
 *
 * All three are confirmed before they run, because all three are terminal: the state machine has no
 * edge out of `COMPLETED`, `NO_SHOW` or `CANCELLED`, so an accidental click is not undoable by
 * clicking again. That is the difference between these and a deactivation, which the same button
 * reverses.
 *
 * **Cancelling has its own endpoint and is not reachable through the status one.** It carries three
 * things a status change cannot express — who cancelled, whether the Cancellation Window is open,
 * and idempotence — and the server refuses `CANCELLED` there with a field error pointing here.
 */
export function AppointmentActions({
  appointment,
  onUpdated,
  onReload,
}: {
  appointment: AppointmentDetail;
  onUpdated: (next: AppointmentWithHistory) => void;
  /** For a `VERSION_CONFLICT`, where the only honest response is to go and read it again. */
  onReload: () => Promise<void>;
}) {
  const toast = useToast();
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');
  const [confirmingStatus, setConfirmingStatus] = useState<ChangeableStatus | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(
    action: () => Promise<AppointmentWithHistory>,
    done: string,
    close: () => void,
  ) {
    setBusy(true);
    try {
      onUpdated(await action());
      toast(done, 'success');
      close();
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'VERSION_CONFLICT') {
        // Somebody else changed this appointment since it was read. Reloading is not a courtesy
        // here — the screen is showing times and a status that are no longer true, and a second
        // attempt from that state would be a guess.
        toast('Somebody else changed this appointment. Reloading it.', 'error');
        close();
        await onReload();
        return;
      }
      toast(cause instanceof ApiError ? cause.message : 'That could not be saved.', 'error');
    } finally {
      setBusy(false);
    }
  }

  if (appointment.status !== 'CONFIRMED') return null;

  return (
    <>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="secondary"
          size="sm"
          onClick={() => setConfirmingStatus('COMPLETED')}
          disabled={busy}
        >
          Mark completed
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onClick={() => setConfirmingStatus('NO_SHOW')}
          disabled={busy}
        >
          Mark no-show
        </Button>
        <Button variant="danger" size="sm" onClick={() => setCancelling(true)} disabled={busy}>
          Cancel appointment
        </Button>
      </div>

      <ConfirmDialog
        open={confirmingStatus !== null}
        title={
          confirmingStatus === 'NO_SHOW' ? 'Mark this as a no-show?' : 'Mark this as completed?'
        }
        confirmLabel={confirmingStatus === 'NO_SHOW' ? 'Mark no-show' : 'Mark completed'}
        busy={busy}
        onConfirm={() =>
          confirmingStatus &&
          void run(
            () => appointmentApi.changeStatus(appointment.id, confirmingStatus),
            confirmingStatus === 'NO_SHOW' ? 'Marked as a no-show.' : 'Marked completed.',
            () => setConfirmingStatus(null),
          )
        }
        onCancel={() => !busy && setConfirmingStatus(null)}
      >
        <p className="text-ink font-medium">
          {appointment.customer.fullName} · {appointment.service.name}
        </p>
        <p>
          {confirmingStatus === 'NO_SHOW'
            ? 'The customer did not arrive. The appointment stays on their history and in your figures.'
            : 'The appointment went ahead. It counts towards revenue from this point on.'}
        </p>
        <p>This cannot be undone — completed and no-show are both final.</p>
      </ConfirmDialog>

      <ConfirmDialog
        open={cancelling}
        title="Cancel this appointment?"
        confirmLabel="Cancel appointment"
        cancelLabel="Keep it"
        tone="danger"
        busy={busy}
        onConfirm={() =>
          void run(
            () =>
              appointmentApi.cancel(appointment.id, reason.trim() ? { reason: reason.trim() } : {}),
            'Appointment cancelled. Its time is bookable again.',
            () => {
              setCancelling(false);
              setReason('');
            },
          )
        }
        onCancel={() => {
          if (busy) return;
          setCancelling(false);
          setReason('');
        }}
      >
        <p className="text-ink font-medium">
          {appointment.customer.fullName} · {appointment.service.name}
        </p>
        <p>
          The time goes back on sale immediately — cancelling is the one status change that frees a
          slot, because a cancelled appointment no longer holds it.
        </p>
        <Textarea
          label="Reason"
          value={reason}
          maxLength={500}
          rows={2}
          onChange={(event) => setReason(event.target.value)}
          hint="Optional, and kept on the record."
        />
      </ConfirmDialog>
    </>
  );
}
