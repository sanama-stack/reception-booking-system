'use client';

import { Button, Card } from '@/components/ui';
import type { BookedAppointment, PublicBusiness } from '@/lib/public';
import { formatDate, formatMoney, formatTimeRange } from '@/lib/time';

/**
 * Booked — and the one moment the Confirmation Code can be shown.
 *
 * The email is on its way but has not arrived, and a Customer who closes this tab without the code
 * has to wait for it. So the code is the largest thing on the screen, selectable, and captioned
 * with what it is *for* rather than merely displayed: a code whose purpose nobody explained is a
 * code nobody keeps.
 *
 * **The Manage Link is not here, and cannot be.** It is a signed capability delivered by email
 * (phase 07), and the booking response deliberately does not carry one — a link on a page anyone
 * could reach is not a proof of anything. What this screen can honestly say is where the link will
 * arrive, and that the code plus the phone number is the other way in.
 */
export function Confirmation({
  appointment,
  business,
  email,
  onBookAnother,
}: {
  appointment: BookedAppointment;
  business: PublicBusiness;
  /** What the Customer actually typed, or `null` when they left it blank — email is optional. */
  email: string | null;
  onBookAnother: () => void;
}) {
  const { timezone } = appointment;

  return (
    <div className="flex flex-col gap-4">
      <Card className="border-success/30">
        <p className="text-success text-sm font-medium tracking-wide uppercase">Booked</p>
        <h2 className="text-ink mt-2 text-xl font-semibold tracking-tight">
          {appointment.service.name} with {appointment.employee.fullName}
        </h2>
        <p className="text-ink mt-1 text-base">
          {formatDate(appointment.startsAt, timezone)},{' '}
          <span className="tabular-nums">
            {formatTimeRange(appointment.startsAt, appointment.endsAt, timezone)}
          </span>
        </p>
        <p className="text-ink-muted mt-1 text-sm">
          {formatMoney(appointment.price.amount, appointment.price.currency)} · times in {timezone}
        </p>

        <div className="border-border mt-5 border-t pt-5">
          <p className="text-ink text-sm font-medium">Your confirmation code</p>
          <p
            // Selectable as a unit, so a tap-and-hold on a phone grabs the whole code rather than
            // one character of it.
            className="text-ink mt-2 font-mono text-3xl font-semibold tracking-[0.2em] select-all"
          >
            {appointment.confirmationCode}
          </p>
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            Keep this. Together with the phone number you booked with, it is how you prove this
            appointment is yours — to {business.name}, or to change it later.
          </p>
        </div>
      </Card>

      <Card>
        <h3 className="text-ink text-sm font-semibold">What happens next</h3>
        {email ? (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            {/*
              "Shortly", not "now". Mail is not sent by the request that causes it: the message is
              written into the outbox inside the booking transaction and a poller drains it
              (ADR-0005), so a minute is the honest promise and "check your inbox" immediately
              would send the Customer looking for something that is not there yet.
            */}
            A confirmation is on its way to <span className="text-ink">{email}</span>, and should
            arrive within a minute or two. It repeats the code above and carries a link that changes
            or cancels this appointment without any password.
          </p>
        ) : (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            You did not give an email address, so there is nothing to send — which means the code
            above is your only copy of it. Write it down before you leave this page.
          </p>
        )}
        {business.cancellationWindowHours > 0 && (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            You can change or cancel it yourself up to{' '}
            {business.cancellationWindowHours === 1
              ? 'an hour'
              : `${business.cancellationWindowHours} hours`}{' '}
            before it starts.
          </p>
        )}
      </Card>

      <div>
        <Button variant="secondary" onClick={onBookAnother}>
          Book another appointment
        </Button>
      </div>
    </div>
  );
}
