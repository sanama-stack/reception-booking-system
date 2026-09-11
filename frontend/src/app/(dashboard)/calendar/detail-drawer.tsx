'use client';

import Link from 'next/link';
import { useEffect, useId, useRef } from 'react';
import { StatusBadge } from '@/components/status-badge';
import { Button, ButtonLink, ErrorState, Spinner } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { appointmentPath, type AppointmentWithHistory } from '@/lib/appointments';
import { formatDateTime, formatMoney, formatTimeRange } from '@/lib/time';

/**
 * The drawer a block opens.
 *
 * **It fetches the full appointment rather than rendering what the calendar already had.** The
 * calendar deliberately carries the customer's name and nothing else about them — no phone, no
 * email, no confirmation code, no price — so that drawing a week of blocks does not ship a week of
 * contact details to the browser. The moment an owner actually opens one appointment is the moment
 * that data is worth fetching, and it is one row rather than a hundred.
 *
 * Built on the native `<dialog>` for the same reasons `ConfirmDialog` is: the focus trap, the inert
 * background and the Escape key are supplied rather than re-implemented. It is positioned as a
 * right-hand panel instead of a centred box, which is a matter of CSS and not of behaviour.
 */
export function DetailDrawer({
  appointmentId,
  onClose,
}: {
  appointmentId: string;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const appointment = useResource<AppointmentWithHistory>(appointmentPath(appointmentId));

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) dialog.showModal();
  }, []);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === ref.current) ref.current?.close();
      }}
      className="bg-surface text-ink border-border mt-0 mr-0 mb-0 ml-auto h-dvh max-h-dvh w-[min(26rem,100vw)] border-l p-6 shadow-lg backdrop:bg-black/40"
    >
      {appointment.data === null && appointment.loading && (
        <div className="flex justify-center py-16" aria-busy="true">
          <Spinner className="text-ink-muted size-6" />
          <span className="sr-only">Loading</span>
        </div>
      )}

      {appointment.data === null && appointment.error && (
        <div className="flex flex-col gap-4">
          <h2 id={titleId} className="text-ink text-base font-semibold">
            Appointment
          </h2>
          <ErrorState error={appointment.error} onRetry={() => void appointment.reload()} />
          <Button variant="secondary" onClick={() => ref.current?.close()}>
            Close
          </Button>
        </div>
      )}

      {appointment.data && (
        <div className="flex h-full flex-col gap-5 overflow-y-auto">
          <header className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 id={titleId} className="text-ink truncate text-base font-semibold">
                {appointment.data.appointment.customer.fullName}
              </h2>
              <p className="text-ink-muted mt-1 text-sm tabular-nums">
                {formatDateTime(appointment.data.appointment.startsAt, appointment.data.timezone)}
              </p>
            </div>
            <Button variant="ghost" size="sm" onClick={() => ref.current?.close()}>
              Close
            </Button>
          </header>

          <StatusBadge status={appointment.data.appointment.status} className="self-start" />

          <dl className="flex flex-col gap-3 text-sm">
            <Row label="When">
              {formatTimeRange(
                appointment.data.appointment.startsAt,
                appointment.data.appointment.endsAt,
                appointment.data.timezone,
              )}
            </Row>
            <Row label="Service">{appointment.data.appointment.service.name}</Row>
            <Row label="With">{appointment.data.appointment.employee.name}</Row>
            <Row label="Phone">
              <a
                href={`tel:${appointment.data.appointment.customer.phone}`}
                className="hover:underline"
              >
                {appointment.data.appointment.customer.phone}
              </a>
            </Row>
            {appointment.data.appointment.customer.email && (
              <Row label="Email">{appointment.data.appointment.customer.email}</Row>
            )}
            {/*
              The snapshot taken at booking, not the service's price today — the same reason the
              appointments table shows this column and not the catalogue's.
            */}
            <Row label="Price">
              {formatMoney(
                appointment.data.appointment.price.amount,
                appointment.data.appointment.price.currency,
              )}
            </Row>
            <Row label="Code">
              <span className="font-mono">{appointment.data.appointment.confirmationCode}</span>
            </Row>
            {appointment.data.appointment.customerNote && (
              <Row label="Note">{appointment.data.appointment.customerNote}</Row>
            )}
          </dl>

          <div className="mt-auto flex flex-wrap gap-2 pt-4">
            <ButtonLink href={`/appointments/${appointment.data.appointment.id}`}>
              Open appointment
            </ButtonLink>
            <Link
              href={`/customers/${appointment.data.appointment.customer.id}`}
              className="text-ink-muted self-center text-sm hover:underline"
            >
              Customer
            </Link>
          </div>
        </div>
      )}
    </dialog>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3">
      <dt className="text-ink-muted w-20 shrink-0">{label}</dt>
      <dd className="text-ink min-w-0 break-words">{children}</dd>
    </div>
  );
}
