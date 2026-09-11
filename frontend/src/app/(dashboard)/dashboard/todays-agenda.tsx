'use client';

import Link from 'next/link';
import { StatusBadge } from '@/components/status-badge';
import { ButtonLink, Card, CardHeader, EmptyState, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { calendarPath, type Calendar, type CalendarAppointment } from '@/lib/calendar';
import {
  addIsoDays,
  formatShortDate,
  formatTimeRange,
  isSameBusinessDate,
  toBusinessDate,
  type IsoDate,
  type Timezone,
} from '@/lib/time';

/** How far past today "next up" is willing to look before admitting it has found nothing. */
const LOOKAHEAD_DAYS = 7;

/**
 * Today, and what is next.
 *
 * **One read, covering both.** The two questions are one question a day wide: "what is left today"
 * and "what is after that" are the same list cut at now, and asking twice would let the second
 * answer come from a moment the first one does not know about. The week of lookahead is what lets
 * this say *nothing else this week* rather than *nothing else today*, which is the difference
 * between a quiet afternoon and a quiet business.
 *
 * `now` is taken once, at mount — like the calendar's current-time line, and for the same reason:
 * nothing here decides anything from it beyond which side of a line an appointment falls on, and
 * re-rendering the page every minute to move that line is not worth what it costs.
 */
export function TodaysAgenda({ timezone }: { timezone: Timezone }) {
  const today = toBusinessDate(new Date(), timezone);
  const agenda = useResource<Calendar>(calendarPath(today, addIsoDays(today, LOOKAHEAD_DAYS)));

  return (
    <ResourceGate resource={agenda}>
      {(data) => {
        const now = new Date();
        const todays = data.appointments.filter((appointment) =>
          isSameBusinessDate(appointment.startsAt, now, timezone),
        );
        // Still to come — by its *end*, so the appointment happening right now is the next one
        // rather than one that has already scrolled past.
        //
        // The first of those really is the soonest: the endpoint answers in start order
        // (`findByBusinessIdAndStartsAtLessThanAndEndsAtGreaterThanOrderByStartsAtAsc`). Named
        // here because it is the one thing on this screen that would break silently if that
        // ordering were ever dropped — it would still render, just pointing at the wrong hour.
        const next =
          data.appointments.find((appointment) => new Date(appointment.endsAt) > now) ?? null;

        return (
          <Card>
            <CardHeader
              title="Today"
              description={`${formatShortDate(now, timezone)} in ${timezone}. Cancelled appointments are not shown — their time is back on sale.`}
            />

            {next && <NextUp appointment={next} today={today} timezone={timezone} />}

            {todays.length === 0 ? (
              <EmptyState
                title="Nothing booked today"
                description={
                  next
                    ? 'The next appointment is further down the week — it is shown above.'
                    : `Nothing in the next ${LOOKAHEAD_DAYS} days either. A booking taken here shows up on this list and in the calendar straight away.`
                }
                action={<ButtonLink href="/appointments/new">Book an appointment</ButtonLink>}
              />
            ) : (
              <ul className="flex flex-col">
                {todays.map((appointment) => (
                  <li
                    key={appointment.id}
                    className="border-border flex flex-wrap items-center gap-x-3 gap-y-1 border-b py-2.5 text-sm last:border-b-0 last:pb-0"
                  >
                    <span className="text-ink w-28 shrink-0 font-medium tabular-nums">
                      {formatTimeRange(appointment.startsAt, appointment.endsAt, timezone)}
                    </span>
                    <Link
                      href={`/appointments/${appointment.id}`}
                      className="text-ink min-w-0 flex-1 truncate hover:underline"
                    >
                      {appointment.customerName}
                    </Link>
                    <span className="text-ink-muted min-w-0 truncate">
                      {appointment.service.name} · {appointment.employee.name}
                    </span>
                    <StatusBadge status={appointment.status} />
                  </li>
                ))}
              </ul>
            )}

            <div className="mt-4">
              <Link href="/calendar" className="text-brand text-sm hover:underline">
                Open the calendar →
              </Link>
            </div>
          </Card>
        );
      }}
    </ResourceGate>
  );
}

/**
 * The one appointment that answers "what am I doing next".
 *
 * It says which day when that day is not today, and only then. A date on every one of them would
 * be noise on the ninety-nine mornings out of a hundred where the answer is "in forty minutes".
 */
function NextUp({
  appointment,
  today,
  timezone,
}: {
  appointment: CalendarAppointment;
  today: IsoDate;
  timezone: Timezone;
}) {
  const day = toBusinessDate(appointment.startsAt, timezone);

  return (
    <div className="border-brand/30 bg-brand/5 mb-4 rounded-lg border p-4">
      <p className="text-ink-muted text-xs tracking-wide uppercase">Next up</p>
      <p className="text-ink mt-1 text-sm font-medium tabular-nums">
        {day === today ? '' : `${formatShortDate(appointment.startsAt, timezone)}, `}
        {formatTimeRange(appointment.startsAt, appointment.endsAt, timezone)}
      </p>
      <p className="text-ink mt-0.5 text-sm">
        {appointment.customerName} — {appointment.service.name} with {appointment.employee.name}
      </p>
    </div>
  );
}
