'use client';

import { cn } from '@/components/ui';
import type { Calendar, CalendarAppointment } from '@/lib/calendar';
import {
  formatIsoDate,
  isoDateWeekday,
  toBusinessDate,
  type IsoDate,
  type Timezone,
} from '@/lib/time';
import { AppointmentBlock, RegionBlock } from './blocks';
import {
  currentMinute,
  daysBetween,
  gridWindow,
  placeSideBySide,
  spanOn,
  type Span,
} from './geometry';
import { TimeGrid, type GridColumn, type GridItem } from './time-grid';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/**
 * Seven days across, everybody's work in each.
 *
 * **Time off is packed beside the appointments here rather than drawn behind them.** A day column
 * holds the whole team, so three people away on Thursday would be three hatched regions stacked
 * exactly on top of each other, and the view would report one absence where there are three.
 * Closures stay behind everything, because a closure really does cover the whole column.
 */
export function WeekView({
  calendar,
  openingHours,
  now,
  onOpen,
  onPick,
}: {
  calendar: Calendar;
  /** The business's hours per weekday (1 is Monday), as a floor for the visible window. */
  openingHours: Map<number, Span[]>;
  now: Date;
  onOpen: (appointment: CalendarAppointment) => void;
  onPick: (date: IsoDate) => void;
}) {
  const timezone: Timezone = calendar.range.timezone;
  const days = daysBetween(calendar.range.from, calendar.range.to);

  const everySpan: Span[] = [];
  const perDay = days.map((date) => {
    const appointments = calendar.appointments
      .map((appointment) => ({
        appointment,
        span: spanOn(date, appointment.startsAt, appointment.endsAt, timezone),
      }))
      .filter(
        (entry): entry is { appointment: CalendarAppointment; span: Span } => entry.span !== null,
      );

    const timeOff = calendar.timeOff
      .map((off) => ({ off, span: spanOn(date, off.startsAt, off.endsAt, timezone) }))
      .filter(
        (entry): entry is { off: (typeof calendar.timeOff)[number]; span: Span } =>
          entry.span !== null,
      );

    const closures = calendar.closures
      .map((closure) => ({
        closure,
        span: spanOn(date, closure.startsAt, closure.endsAt, timezone),
      }))
      .filter(
        (entry): entry is { closure: (typeof calendar.closures)[number]; span: Span } =>
          entry.span !== null,
      );

    // Only the appointments widen the window — see the day view for why regions are clipped
    // rather than allowed to stretch a closed day to twenty-four hours of hatching.
    everySpan.push(...appointments.map((entry) => entry.span));

    return { date, appointments, timeOff, closures };
  });

  const window = gridWindow(everySpan, [...openingHours.values()].flat());

  // Which column is today, and — separately — whether the line can be drawn in it. A business
  // open until 22:00 is still looking at today's column at midnight; it just has no line in it.
  const today = days.find((date) => date === toBusinessDate(now, timezone)) ?? null;
  const nowMinute = today === null ? null : currentMinute(now, timezone, today, window);

  const columns: GridColumn[] = perDay.map(({ date, appointments, timeOff, closures }) => {
    // One packing over both kinds, so an absence and a booking at the same hour sit side by side
    // instead of one hiding the other.
    const packed = placeSideBySide<
      | { kind: 'appointment'; value: CalendarAppointment }
      | { kind: 'off'; value: (typeof timeOff)[number]['off'] }
    >([
      ...appointments.map((entry) => ({
        item: { kind: 'appointment' as const, value: entry.appointment },
        span: entry.span,
      })),
      ...timeOff.map((entry) => ({
        item: { kind: 'off' as const, value: entry.off },
        span: entry.span,
      })),
    ]);

    const items: GridItem[] = packed.map((placed) => {
      const entry = placed.item;
      return {
        key: `${entry.kind}-${entry.value.id}`,
        span: placed.span,
        lane: placed.lane,
        lanes: placed.lanes,
        node:
          entry.kind === 'appointment' ? (
            <AppointmentBlock
              appointment={entry.value}
              minutes={placed.span.endMinute - placed.span.startMinute}
              timezone={timezone}
              compact
              onOpen={() => onOpen(entry.value)}
            />
          ) : (
            <RegionBlock label={`${entry.value.employee.name} away`} detail={entry.value.reason} />
          ),
      };
    });

    const regions: GridItem[] = closures.map((entry) => ({
      key: `closure-${entry.closure.id}-${date}`,
      span: entry.span,
      lane: 0,
      lanes: 1,
      node: <RegionBlock label="Closed" detail={entry.closure.reason} />,
    }));

    const weekday = WEEKDAYS[isoDateWeekday(date) - 1];
    const isToday = date === today;

    return {
      key: date,
      header: (
        <span className={cn('min-w-0', isToday && 'text-brand')}>
          <span className="block text-[11px] tracking-wide uppercase">{weekday}</span>
          <span className="text-ink block truncate text-sm font-medium tabular-nums">
            {Number(date.slice(8))}
          </span>
        </span>
      ),
      regions,
      items,
      onPick: () => onPick(date),
      pickLabel: `Book on ${formatIsoDate(date)}`,
    };
  });

  return (
    <TimeGrid
      window={window}
      columns={columns}
      // Only today's column gets the line: 14:20 on Thursday is not a fact about Tuesday.
      now={today !== null && nowMinute !== null ? { minute: nowMinute, columnKey: today } : null}
    />
  );
}
