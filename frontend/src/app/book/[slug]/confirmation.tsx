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
 * could reach is not a proof of anything. What this screen can honestly say is *that* the link is
 * coming, and that the code plus the phone number is the other way in.
 *
 * **It cannot say where it is going, and must not try.** A Customer is identified by phone number
 * alone, so a returning number keeps the email already on file: the address in the form may not be
 * the recipient, and may not exist at all. `appointment.confirmationSent` is the server's answer to
 * "was anything actually sent", and the three branches below are keyed on it rather than on what
 * was typed — which is what this screen used to trust, and got wrong in both directions
 * (ADR-0007).
 */
export function Confirmation({
  appointment,
  business,
  email,
  onBookAnother,
}: {
  appointment: BookedAppointment;
  business: PublicBusiness;
  /**
   * What the Customer actually typed, `null` when they left it blank, and **`undefined` when this
   * screen has no way to know** — email is optional, and the Receptionist collects it in
   * conversation rather than in a form this component can read.
   *
   * **Not the recipient, and not evidence a message was sent.** It is used only to tell the
   * "nothing was sent" cases apart, and the third state exists because conflating "not known" with
   * "left blank" would put a false sentence on the screen: the Classic Flow can say *you did not
   * give an address*, and the chat panel cannot.
   * `appointment.confirmationSent` decides whether anything went at all.
   */
  email?: string | null;
  /** Omitted where there is nothing to reset — in the chat panel, booking again is done by asking. */
  onBookAnother?: () => void;
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
        {appointment.confirmationSent ? (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            {/*
              "Shortly", not "now". Mail is not sent by the request that causes it: the message is
              written into the outbox inside the booking transaction and a poller drains it
              (ADR-0005), so a minute is the honest promise and "check your inbox" immediately
              would send the Customer looking for something that is not there yet.

              And "the address on file", not the address they typed. It is usually the same address
              — but a returning phone number keeps the email already stored against it, so naming
              the typed one would have been a guess this screen is in no position to make. See
              ADR-0007; `confirmationSent` is the only thing here that knows.
            */}
            A confirmation is on its way to the email address on file for this number, and should
            arrive within a minute or two. It repeats the code above and carries a link that changes
            or cancels this appointment without any password.
          </p>
        ) : email ? (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            {/*
              They typed an address and are getting nothing — the surprising branch, and the one
              this screen used to get wrong. This phone number is already known here without an
              email address, and a booking deliberately does not overwrite what is stored, so there
              is no address to send to. Saying so plainly beats a promise that never arrives.
            */}
            {/*
              `font-medium`, not colour alone. This paragraph is `text-ink-muted`, so `text-ink`
              would darken the clause here — but the identical sentence on the manage page sits in a
              `text-ink` banner, where it did nothing at all (issue #8). Emphasis carries its own
              weight so it cannot depend on what colour the parent happens to be.
            */}
            <span className="text-ink font-medium">No confirmation email is being sent.</span> This
            phone number is already on file with {business.name} without an email address, and
            booking does not change the details they hold — so the code above is your only copy of
            it. Write it down before you leave this page, and ask {business.name} to add{' '}
            <span className="text-ink font-medium">{email}</span> if you would like messages in
            future.
          </p>
        ) : email === null ? (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            You did not give an email address, so there is nothing to send — which means the code
            above is your only copy of it. Write it down before you leave this page.
          </p>
        ) : (
          <p className="text-ink-muted mt-2 text-sm leading-relaxed">
            {/*
              The Receptionist's booking. What was said to it is not readable from here, so the two
              sentences above — "you did not give an address" and "the address you gave cannot be
              used" — are both claims this branch is in no position to make. What
              `confirmationSent` does establish is the part that matters to the Customer: nothing
              is coming, so the code on this screen is the only copy of it.
            */}
            <span className="text-ink font-medium">No confirmation email is being sent.</span> There
            is no email address on file with {business.name} for this number, so the code above is
            your only copy of it. Write it down before you leave this page.
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

      {onBookAnother && (
        <div>
          <Button variant="secondary" onClick={onBookAnother}>
            Book another appointment
          </Button>
        </div>
      )}
    </div>
  );
}
