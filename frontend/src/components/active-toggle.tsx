'use client';

import { useState } from 'react';
import { Button, ConfirmDialog, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';

type Subject = 'service' | 'employee';

/**
 * The words differ between the two subjects and nothing else does, so they sit together here
 * rather than being passed in from four call sites that would each have to get them right.
 */
const COPY: Record<Subject, { title: string; consequence: string; deactivated: string }> = {
  service: {
    title: 'Deactivate this service?',
    consequence:
      'Customers will no longer be able to book it. It stays in your list, and in the history of every appointment already made for it.',
    deactivated: 'Service deactivated.',
  },
  employee: {
    title: 'Deactivate this person?',
    consequence:
      'Customers will no longer be able to book appointments with them, and any service only they provide becomes unbookable. Their record and their history stay.',
    deactivated: 'Employee deactivated.',
  },
};

/**
 * Activate and deactivate, with the confirmation a deactivation deserves.
 *
 * Activation is not confirmed: it takes nothing away, and undoing it is this same button.
 *
 * The count of affected appointments can only be known *after* the write — it is what the endpoint
 * answers with — so the dialog promises that nothing is cancelled and the result reports what was
 * affected. It is `0` until appointments exist, and is reported rather than assumed: when phase 06
 * makes it non-zero, this says so without being touched.
 */
export function ActiveToggle({
  active,
  subject,
  name,
  onToggle,
  size = 'sm',
}: {
  active: boolean;
  subject: Subject;
  /** The subject's own name, so the dialog says what is about to change. */
  name: string;
  /** Performs the write and answers with `affectedFutureAppointments`. */
  onToggle: (active: boolean) => Promise<number>;
  size?: 'sm' | 'md';
}) {
  const toast = useToast();
  const copy = COPY[subject];
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run(next: boolean) {
    setBusy(true);
    try {
      const affected = await onToggle(next);
      setConfirming(false);
      if (next) {
        toast(subject === 'service' ? 'Service activated.' : 'Employee activated.', 'success');
      } else {
        toast(
          affected > 0
            ? `${copy.deactivated} There ${affected === 1 ? 'is' : 'are'} still ${affected} upcoming ${
                affected === 1 ? 'appointment' : 'appointments'
              }, which have not been cancelled.`
            : copy.deactivated,
          affected > 0 ? 'info' : 'success',
        );
      }
    } catch (cause) {
      toast(cause instanceof ApiError ? cause.message : 'That change could not be saved.', 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button
        variant="secondary"
        size={size}
        loading={busy && !confirming}
        onClick={() => (active ? setConfirming(true) : void run(true))}
      >
        {active ? 'Deactivate' : 'Activate'}
      </Button>

      <ConfirmDialog
        open={confirming}
        title={copy.title}
        confirmLabel="Deactivate"
        tone="danger"
        busy={busy}
        onConfirm={() => void run(false)}
        onCancel={() => !busy && setConfirming(false)}
      >
        <p className="text-ink font-medium">{name}</p>
        <p>{copy.consequence}</p>
        <p>Nothing already booked is cancelled. You can activate it again at any time.</p>
      </ConfirmDialog>
    </>
  );
}
