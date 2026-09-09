import { StatusBadge } from '@/components/status-badge';
import { Card } from '@/components/ui';
import { formatDuration } from '@/lib/catalog';
import type { ManagedAppointment } from '@/lib/public';
import { formatDate, formatMoney, formatTimeRange } from '@/lib/time';

/**
 * What was booked, when, with whom, and for how much.
 *
 * The whole point of the page: a Customer arriving from an email six weeks later mostly wants to
 * *check* their appointment, and cancelling or moving it is the less common errand. So the summary
 * is the first thing and the actions follow it.
 *
 * `StatusBadge` is reused from the dashboard rather than reimplemented. It renders the same closed
 * enum, and its four tones were chosen from what each status means rather than from an owner's
 * point of view — a cancelled appointment is history and is quiet here for the same reason it is
 * quiet in a list. The *sentence* beside it is written for this reader and is not shared.
 */
export function AppointmentSummary({ appointment }: { appointment: ManagedAppointment }) {
  const { timezone } = appointment;

  return (
    <Card>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-ink text-lg font-semibold tracking-tight">
            {appointment.service.name}
          </h2>
          <p className="text-ink-muted mt-0.5 text-sm">
            with {appointment.employee.fullName} ·{' '}
            {formatDuration(appointment.service.durationMinutes)}
          </p>
        </div>
        <StatusBadge status={appointment.status} />
      </div>

      <p className="text-ink mt-4 text-base">
        {formatDate(appointment.startsAt, timezone)},{' '}
        <span className="tabular-nums">
          {formatTimeRange(appointment.startsAt, appointment.endsAt, timezone)}
        </span>
      </p>
      <p className="text-ink-muted mt-1 text-sm">
        {formatMoney(appointment.price.amount, appointment.price.currency)} ·{' '}
        {/* The zone, stated rather than assumed. Every time on this page is the business's own
            wall clock, and a Customer in another country has to be told which one that is. */}
        times in {timezone}
      </p>

      <StatusNote appointment={appointment} />

      {appointment.note && (
        <div className="border-border mt-5 border-t pt-5">
          <p className="text-ink text-sm font-medium">What you asked them to know</p>
          <p className="text-ink-muted mt-1 text-sm leading-relaxed whitespace-pre-line">
            {appointment.note}
          </p>
        </div>
      )}

      <div className="border-border mt-5 border-t pt-5">
        <p className="text-ink text-sm font-medium">Your confirmation code</p>
        <p
          // Selectable as a unit, so a tap-and-hold on a phone grabs the whole code rather than
          // one character of it — the same treatment the booking confirmation gives it.
          className="text-ink mt-2 font-mono text-2xl font-semibold tracking-[0.2em] select-all"
        >
          {appointment.confirmationCode}
        </p>
        <p className="text-ink-muted mt-2 text-sm leading-relaxed">
          Quote it to {appointment.business.name} and they can find this booking straight away.
        </p>
      </div>
    </Card>
  );
}

/**
 * What the status means for the person reading it.
 *
 * `CONFIRMED` gets nothing: the summary above already says the appointment is happening, and a
 * line saying so again would be the page talking to itself. The other three are each a fact the
 * Customer may not know, which is exactly why they are worth a sentence — somebody opening an old
 * link to check a time needs to be told plainly that there is no longer an appointment to check.
 */
function StatusNote({ appointment }: { appointment: ManagedAppointment }) {
  if (appointment.status === 'CONFIRMED') return null;

  const message =
    appointment.status === 'CANCELLED'
      ? 'This appointment was cancelled. Nothing is booked for you at this time — book again if you still want it.'
      : appointment.status === 'COMPLETED'
        ? 'This appointment has already taken place.'
        : `${appointment.business.name} recorded this as a missed appointment.`;

  return (
    <p className="border-border bg-surface-muted text-ink-muted mt-4 rounded-md border px-3 py-2 text-sm leading-relaxed">
      {message}
    </p>
  );
}
