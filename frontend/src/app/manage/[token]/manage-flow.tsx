'use client';

import { useState } from 'react';
import { Button, Card, CardHeader } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { publicApi, type ManagedAppointment, type ManagingBusiness } from '@/lib/public';
import type { IsoDate } from '@/lib/time';
import { AppointmentSummary } from './appointment-summary';
import { CancelDialog } from './cancel-dialog';
import { RescheduleCard } from './reschedule-card';

/** What just happened, so the page can say so before the summary that quietly proves it. */
type Outcome = 'cancelled' | 'moved';

/**
 * The appointment, and the two things its owner may do to it.
 *
 * **Every write returns the appointment it produced**, so this component adopts the body rather
 * than re-reading — the same bargain `useResource.set` strikes on the dashboard. A cancel returns
 * a `CANCELLED` appointment, a reschedule returns one at its new time, and in both cases the
 * summary above is re-rendered from the server's own answer instead of from an optimistic guess
 * about what the server did.
 *
 * **The refusals are the interesting half.** `canCancel` and `canReschedule` arrive already
 * combining the Cancellation Window with the status, which is what makes it possible to show the
 * business's policy beside a disabled action rather than letting the Customer press it and be
 * told no. They are hints and never controls: the endpoint checks again, and when its answer
 * disagrees with this screen the screen is the one that is wrong — so it is re-resolved rather
 * than argued with.
 */
export function ManageFlow({
  token,
  initial,
  today,
}: {
  token: string;
  initial: ManagedAppointment;
  /** Today in the business's timezone, resolved on the server. Anchors the reschedule grid. */
  today: IsoDate;
}) {
  const [appointment, setAppointment] = useState(initial);
  const [moving, setMoving] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  /**
   * Go and read the appointment again, because this screen has just been shown to be out of date.
   *
   * Reached from a refusal the page thought could not happen — the window shutting between the
   * render and the press, or somebody at the business cancelling first. The alternative is to flip
   * a local flag and keep rendering from a picture already known to be wrong, which would then be
   * wrong about the *next* thing too. One extra round trip on a rare path buys a screen that
   * agrees with the server.
   *
   * A failure here is swallowed on purpose: the refusal that sent us here is already on screen and
   * is the message that matters, and replacing it with "could not refresh" would report the
   * recovery instead of the problem.
   */
  async function resolveAgain() {
    try {
      setAppointment(await publicApi.manage(token));
    } catch {
      /* keep the refusal on screen */
    }
  }

  /** Codes that mean this page's copy of the appointment is stale, whatever it was trying to do. */
  function isStaleAppointment(code: ApiError['code']): boolean {
    return (
      code === 'CANCELLATION_WINDOW_CLOSED' ||
      code === 'VERSION_CONFLICT' ||
      code === 'INVALID_STATUS_TRANSITION'
    );
  }

  async function onCancelled(next: ManagedAppointment) {
    setAppointment(next);
    setCancelling(false);
    setMoving(false);
    setOutcome('cancelled');
    setError(null);
  }

  async function onMoved(next: ManagedAppointment) {
    setAppointment(next);
    setMoving(false);
    setOutcome('moved');
    setError(null);
  }

  /** Shared by both writes: report it, and re-resolve when the report says we were out of date. */
  async function onFailed(cause: ApiError) {
    setError(cause);
    setOutcome(null);
    if (isStaleAppointment(cause.code)) {
      setCancelling(false);
      setMoving(false);
      await resolveAgain();
    }
  }

  const { business } = appointment;
  const canAct = appointment.canCancel || appointment.canReschedule;

  return (
    <div className="flex flex-col gap-4">
      {outcome && <OutcomeBanner outcome={outcome} appointment={appointment} />}

      {error && (
        <p
          role="alert"
          className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
        >
          {error.message}
          {error.retryAfterSeconds !== undefined && (
            <span className="mt-1 block">
              Try again in {error.retryAfterSeconds}{' '}
              {error.retryAfterSeconds === 1 ? 'second' : 'seconds'}.
            </span>
          )}
        </p>
      )}

      <AppointmentSummary appointment={appointment} />

      {moving ? (
        <RescheduleCard
          token={token}
          appointment={appointment}
          today={today}
          onMoved={onMoved}
          onFailed={onFailed}
          onDone={() => setMoving(false)}
        />
      ) : canAct ? (
        <Card>
          <CardHeader
            title="Need to change it?"
            description="No password and no account — this link is all you need."
          />
          {/* `min-h-11` — 44 px, the thumb guideline, over the shared `Button`'s 40 px. */}
          <div className="flex flex-wrap gap-2">
            {appointment.canReschedule && (
              <Button
                className="min-h-11"
                onClick={() => {
                  setMoving(true);
                  setOutcome(null);
                  setError(null);
                }}
              >
                Move to another time
              </Button>
            )}
            {appointment.canCancel && (
              <Button
                className="min-h-11"
                variant="danger"
                onClick={() => {
                  setCancelling(true);
                  setOutcome(null);
                  setError(null);
                }}
              >
                Cancel appointment
              </Button>
            )}
          </div>
          {business.cancellationPolicy && (
            <p className="text-ink-muted mt-4 text-sm leading-relaxed whitespace-pre-line">
              {business.cancellationPolicy}
            </p>
          )}
        </Card>
      ) : (
        <NoLongerChangeable appointment={appointment} />
      )}

      {/*
        Mounted only while cancelling is actually on offer. A closed `<dialog>` is inert and
        invisible, so leaving one here permanently would harm nobody — but a page that has just
        refused to cancel an appointment should not be carrying the machinery to cancel it, and
        "the confirm dialog is in the DOM of the refusal screen" is the kind of thing that reads as
        a bug to the next person to look.
      */}
      {appointment.canCancel && (
        <CancelDialog
          open={cancelling}
          token={token}
          appointment={appointment}
          onCancelled={onCancelled}
          onFailed={onFailed}
          onDismiss={() => setCancelling(false)}
        />
      )}
    </div>
  );
}

/** Said once, at the top, because the summary below states the new facts without narrating them. */
function OutcomeBanner({
  outcome,
  appointment,
}: {
  outcome: Outcome;
  appointment: ManagedAppointment;
}) {
  return (
    <p
      // `role="status"` rather than `alert`: this is the successful end of something the Customer
      // just did, and an assertive interruption is for things that have gone wrong.
      role="status"
      className="border-success/30 bg-success/5 text-ink rounded-md border px-3 py-2 text-sm leading-relaxed"
    >
      {outcome === 'cancelled'
        ? `Cancelled. ${appointment.business.name} has been told, and the time is free for somebody else.`
        : 'Moved. The new time is below.'}{' '}
      {/* "Shortly", never "now": mail is written into the outbox inside the transaction and a
          poller drains it (ADR-0005), so a minute is the honest promise. */}
      A confirmation email should reach you within a minute or two.
    </p>
  );
}

/**
 * The policy-aware refusal the phase document asks for.
 *
 * Two quite different situations reach here and they must not be given the same sentence. An
 * appointment that is no longer `CONFIRMED` has nothing left to change — the summary above has
 * already said what became of it — whereas a confirmed one with the window shut is a live
 * appointment that the Customer simply cannot alter *themselves* any more. The second is the one
 * that needs a way forward, and giving it a phone number is the entire reason `ManagingBusiness`
 * carries one.
 *
 * **It cannot say how long the window was**, because the deadline is not on the wire — see
 * `ManagingBusiness`. That turns out to matter less than it sounds: the number is only useful
 * before it passes, and by the time anybody reads this it has.
 */
function NoLongerChangeable({ appointment }: { appointment: ManagedAppointment }) {
  const settled = appointment.status !== 'CONFIRMED';

  return (
    <Card>
      <CardHeader
        title={settled ? 'Nothing left to change' : 'Too late to change this online'}
        description={
          settled
            ? 'This appointment is closed, so there is nothing here to move or cancel.'
            : `The time by which ${appointment.business.name} needs notice has passed, so this appointment can no longer be moved or cancelled from this page.`
        }
      />
      {appointment.business.cancellationPolicy && (
        <p className="text-ink-muted text-sm leading-relaxed whitespace-pre-line">
          {appointment.business.cancellationPolicy}
        </p>
      )}
      {!settled && <ContactThem business={appointment.business} />}
    </Card>
  );
}

/**
 * How to reach a human, when the page has just said no.
 *
 * A refusal that says "contact the business" without saying how is a dead end — the one failure
 * mode the phase document names for this screen. When the business has published neither a phone
 * number nor an email address there is nothing honest to offer, so the page says that rather than
 * printing an empty heading.
 */
function ContactThem({ business }: { business: ManagingBusiness }) {
  if (!business.phone && !business.email) {
    return (
      <p className="text-ink-muted mt-4 text-sm leading-relaxed">
        {business.name} has not published a phone number or an email address here. The confirmation
        email you were sent is the best place to look for one.
      </p>
    );
  }

  return (
    <div className="border-border mt-4 border-t pt-4">
      <p className="text-ink text-sm font-medium">Contact {business.name}</p>
      <ul className="mt-2 flex flex-col gap-1 text-sm">
        {business.phone && (
          <li>
            <a className="text-brand hover:underline" href={`tel:${business.phone}`}>
              {business.phone}
            </a>
          </li>
        )}
        {business.email && (
          <li>
            <a className="text-brand hover:underline" href={`mailto:${business.email}`}>
              {business.email}
            </a>
          </li>
        )}
      </ul>
    </div>
  );
}
