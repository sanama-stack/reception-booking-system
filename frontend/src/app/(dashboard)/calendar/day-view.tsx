'use client';

import type { Calendar, CalendarAppointment } from '@/lib/calendar';
import type { EmployeeDetail } from '@/lib/staff';
import { formatIsoDate, type IsoDate, type Timezone } from '@/lib/time';
import { AppointmentBlock, RegionBlock } from './blocks';
import { currentMinute, gridWindow, placeSideBySide, spanOn, type Span } from './geometry';
import { TimeGrid, type GridColumn, type GridItem } from './time-grid';

/**
 * One day, a column per person.
 *
 * **Every employee gets a column, including the ones with nothing booked.** Deriving the columns
 * from the appointments would make the emptiest and most interesting column — the person who is
 * free all afternoon — the one column the view does not draw.
 *
 * An inactive employee appears only if the day actually has something of theirs on it. They are not
 * bookable, so a standing column would be an invitation to click on nothing; a column they already
 * own is the alternative to hiding an appointment that exists.
 */
export function DayView({
  date,
  calendar,
  employees,
  openingHours,
  now,
  onOpen,
  onPick,
}: {
  date: IsoDate;
  calendar: Calendar;
  employees: EmployeeDetail[];
  /** The business's hours for this day, as a floor for the visible window. Empty means closed. */
  openingHours: Span[];
  now: Date;
  onOpen: (appointment: CalendarAppointment) => void;
  onPick: (employeeId: string) => void;
}) {
  const timezone: Timezone = calendar.range.timezone;

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
    .filter((entry) => entry.span !== null);

  const closures = calendar.closures
    .map((closure) => ({ closure, span: spanOn(date, closure.startsAt, closure.endsAt, timezone) }))
    .filter((entry) => entry.span !== null);

  const present = new Set([
    ...appointments.map((entry) => entry.appointment.employee.id),
    ...timeOff.map((entry) => entry.off.employee.id),
  ]);
  const columns = employees
    .filter((employee) => employee.active || present.has(employee.id))
    .sort((a, b) => a.fullName.localeCompare(b.fullName));

  // **Appointments widen the window; regions do not.** A booking outside opening hours must never
  // be off-screen — that is the one thing a calendar may not do. A closure and a time off are whole
  // days by construction (both are entered as dates), so letting them widen it would stretch every
  // closed day to a full twenty-four hours of hatching and squeeze the hours anything happens in
  // into a sliver. Clipping them costs nothing: a region carries its meaning in its label, not in
  // its height.
  const window = gridWindow(
    appointments.map((entry) => entry.span),
    openingHours,
  );

  const gridColumns: GridColumn[] = columns.map((employee) => {
    const theirs = appointments.filter((entry) => entry.appointment.employee.id === employee.id);

    const items: GridItem[] = placeSideBySide(
      theirs.map((entry) => ({ item: entry.appointment, span: entry.span })),
    ).map((placed) => ({
      key: placed.item.id,
      span: placed.span,
      lane: placed.lane,
      lanes: placed.lanes,
      node: (
        <AppointmentBlock
          appointment={placed.item}
          minutes={placed.span.endMinute - placed.span.startMinute}
          timezone={timezone}
          compact={false}
          onOpen={() => onOpen(placed.item)}
        />
      ),
    }));

    // A closure covers everybody, so it is drawn in every column rather than announced once above
    // the grid: the owner reads this view column by column, and a banner is not where they look.
    const regions: GridItem[] = [
      ...closures.map((entry) => ({
        key: `closure-${entry.closure.id}-${employee.id}`,
        span: entry.span as Span,
        lane: 0,
        lanes: 1,
        node: <RegionBlock label="Closed" detail={entry.closure.reason} />,
      })),
      ...timeOff
        .filter((entry) => entry.off.employee.id === employee.id)
        .map((entry) => ({
          key: `off-${entry.off.id}`,
          span: entry.span as Span,
          lane: 0,
          lanes: 1,
          node: <RegionBlock label="Away" detail={entry.off.reason} />,
        })),
    ];

    return {
      key: employee.id,
      header: (
        <span className="min-w-0">
          <span className="text-ink block truncate text-sm font-medium">{employee.fullName}</span>
          {!employee.active && <span className="text-ink-muted block text-[11px]">inactive</span>}
        </span>
      ),
      regions,
      items,
      // An inactive person cannot be booked, so their column does not offer it. The server would
      // refuse it anyway with `EMPLOYEE_INACTIVE`; offering a click that ends in a refusal is a
      // worse way to learn that than not offering it.
      ...(employee.active
        ? {
            onPick: () => onPick(employee.id),
            pickLabel: `Book with ${employee.fullName} on ${formatIsoDate(date)}`,
          }
        : {}),
    };
  });

  const nowMinute = currentMinute(now, timezone, date, window);

  return (
    <TimeGrid
      window={window}
      columns={gridColumns}
      // Every column here is the same day, so the line crosses all of them.
      now={nowMinute === null ? null : { minute: nowMinute, columnKey: null }}
    />
  );
}
