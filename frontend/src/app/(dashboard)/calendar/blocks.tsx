'use client';

import { cn } from '@/components/ui';
import type { CalendarAppointment } from '@/lib/calendar';
import { formatTimeRange, type Timezone } from '@/lib/time';

/**
 * What a block looks like. Three kinds, and the difference between them is the point of the view:
 * a coloured rectangle is time sold, a hatched one is time that was never for sale.
 */

/**
 * Tone by status, matching `StatusBadge`'s reasoning: `CONFIRMED` is the brand colour because it is
 * the only status holding time against the exclusion constraint, `NO_SHOW` is warning because
 * nothing failed but somebody must decide, and `COMPLETED` is settled.
 *
 * `CANCELLED` has no entry and needs none — the endpoint does not send cancelled appointments,
 * because their time was released the instant they were cancelled and a block over free time is
 * how a double booking starts.
 */
const TONES: Record<CalendarAppointment['status'], string> = {
  CONFIRMED: 'border-brand/40 bg-brand/10 text-ink hover:bg-brand/15',
  COMPLETED: 'border-success/40 bg-success/10 text-ink hover:bg-success/15',
  NO_SHOW: 'border-warning/50 bg-warning/10 text-ink hover:bg-warning/20',
  CANCELLED: 'border-border bg-surface-muted text-ink-muted',
};

/**
 * Below this many minutes a block is one line, not three.
 *
 * A block is as tall as the appointment is long — that is the whole point of the view — so a short
 * one has nowhere to put three lines, and was rendering the second sliced in half by its own bottom
 * edge. Half a name reads as a rendering fault rather than as a short appointment. One line saying
 * the time and who it is for fits; the rest is one click away in the drawer.
 */
const ROOM_FOR_THREE_LINES = 45;

export function AppointmentBlock({
  appointment,
  minutes,
  timezone,
  compact,
  onOpen,
}: {
  appointment: CalendarAppointment;
  /** How long it is, which is how tall it will be drawn. */
  minutes: number;
  timezone: Timezone;
  /** Week view: the column is a whole day's worth of people, so there is room for less. */
  compact: boolean;
  onOpen: () => void;
}) {
  const time = formatTimeRange(appointment.startsAt, appointment.endsAt, timezone);

  return (
    <button
      type="button"
      onClick={onOpen}
      // `flex-col` rather than the default: a button centres its content vertically, so a
      // two-hour block drew its label across the middle of itself, reading as an appointment
      // that starts an hour after it does.
      className={cn(
        'focus-visible:ring-brand flex h-full w-full flex-col items-start overflow-hidden rounded-md border px-1.5 py-1 text-left leading-tight focus-visible:ring-2 focus-visible:outline-none',
        TONES[appointment.status],
      )}
    >
      {minutes < ROOM_FOR_THREE_LINES ? (
        <span className="flex w-full min-w-0 items-center gap-1">
          <span className="shrink-0 text-[11px] font-medium tabular-nums">{time}</span>
          <span className="truncate text-[11px]">{appointment.customerName}</span>
          {appointment.source === 'AI' && <AiBadge />}
        </span>
      ) : (
        <>
          <span className="flex w-full min-w-0 items-center gap-1">
            <span className="truncate text-[11px] font-medium tabular-nums">{time}</span>
            {appointment.source === 'AI' && <AiBadge />}
          </span>
          <span className="w-full truncate text-xs font-medium">{appointment.customerName}</span>
          <span className="text-ink-muted w-full truncate text-[11px]">
            {appointment.service.name}
            {compact ? ` · ${appointment.employee.name}` : ''}
          </span>
        </>
      )}
    </button>
  );
}

/**
 * The AI source badge the phase asks for.
 *
 * A letter rather than a word because it has to survive a 30-minute block in a week column, and a
 * title because an unexplained badge is a puzzle. `DASHBOARD` and `CLASSIC` get nothing: the badge
 * exists to answer "did the receptionist book this?", and marking the other two would make every
 * block carry a label that says nothing.
 */
function AiBadge() {
  return (
    <span
      title="Booked by the AI receptionist"
      className="border-brand/40 text-brand bg-surface inline-flex shrink-0 items-center rounded border px-1 text-[10px] leading-4 font-semibold"
    >
      AI
      <span className="sr-only"> — booked by the AI receptionist</span>
    </span>
  );
}

/**
 * A closure or a time off, drawn as a region rather than a block.
 *
 * Hatched and unclickable on purpose: it is not something that was booked and there is nothing to
 * open. It is here so that an empty column is *explicable* — an empty Tuesday looks identical
 * whether the business was shut, the only eligible person was away, or nobody booked, and the
 * owner acts differently on each of the three.
 */
export function RegionBlock({ label, detail }: { label: string; detail: string | null }) {
  return (
    <div
      title={detail ? `${label} — ${detail}` : label}
      className="border-border/80 text-ink-muted h-full w-full overflow-hidden rounded-md border border-dashed [background-image:repeating-linear-gradient(45deg,transparent,transparent_5px,var(--color-surface-muted)_5px,var(--color-surface-muted)_10px)] px-1.5 py-1 text-[11px] leading-tight"
    >
      <span className="block truncate font-medium">{label}</span>
      {detail && <span className="block truncate">{detail}</span>}
    </div>
  );
}
