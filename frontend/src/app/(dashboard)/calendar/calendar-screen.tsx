'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button, ButtonLink, EmptyState, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { Calendar, CalendarAppointment } from '@/lib/calendar';
import type { EmployeeDetail } from '@/lib/staff';
import { formatIsoDate, isoDateWeekday, type IsoDate } from '@/lib/time';
import { DayView } from './day-view';
import type { Span } from './geometry';
import { DetailDrawer } from './detail-drawer';
import { WeekView } from './week-view';

/**
 * One calendar, keyed on its own question by the caller.
 *
 * `useResource` keeps the data it last loaded when a later load fails, which is right for a screen
 * reloading one resource and wrong here: the previous answer was about a different week, and
 * leaving it under a changed date would draw last Tuesday's bookings under this Tuesday's heading.
 * A remount makes "this is a new question" the only thing this component can say — the same rule
 * the appointments table follows.
 *
 * **`now` is taken once, at mount.** A minute-by-minute clock would re-render every block in the
 * grid sixty times an hour to move one red line, and the line is already re-placed by every
 * navigation. Nothing here decides anything from it; it positions a line and highlights a column.
 */
export function CalendarScreen({
  path,
  view,
  date,
  employees,
  openingHours,
}: {
  path: string;
  view: 'day' | 'week';
  /** The anchor day. In week view it is a day inside the week being drawn. */
  date: IsoDate;
  employees: EmployeeDetail[];
  /** Opening hours per ISO weekday, 1 being Monday. A weekday with no entry is closed. */
  openingHours: Map<number, Span[]>;
}) {
  const router = useRouter();
  const calendar = useResource<Calendar>(path);
  const [opened, setOpened] = useState<CalendarAppointment | null>(null);
  const [now] = useState(() => new Date());

  /**
   * Clicking empty space carries the **day and the person**, and deliberately not the minute.
   *
   * A minute on a grid is not a bookable time. Which times exist depends on the service's
   * duration, its buffers, the employee's working hours and what is already booked — all of which
   * the availability engine answers and none of which this grid knows. Carrying `10:07` into the
   * booking form would either invent a slot the engine would refuse or quietly round to one the
   * owner did not click. The two answers the click really does contain are carried, and the times
   * come from the same read every other booking screen uses.
   */
  function book(employeeId: string | null, day: IsoDate) {
    const query = new URLSearchParams({ date: day });
    if (employeeId) query.set('employeeId', employeeId);
    router.push(`/appointments/new?${query.toString()}`);
  }

  return (
    <>
      <ResourceGate resource={calendar}>
        {(data) => {
          if (employees.length === 0) {
            return (
              <EmptyState
                title="No one to show"
                description="A calendar is a column per person. Add an employee and their day appears here, along with anything booked with them."
                action={<ButtonLink href="/employees/new">Add an employee</ButtonLink>}
              />
            );
          }

          const empty =
            data.appointments.length === 0 &&
            data.closures.length === 0 &&
            data.timeOff.length === 0;

          return (
            <div className="flex flex-col gap-3">
              {/*
                A note above the grid rather than instead of it. An empty calendar is not an empty
                list: the hours, the columns and the current-time line are all still answers, and
                replacing them with a card would take away the thing that says *nothing is booked
                here* as opposed to *this screen has not loaded*.
              */}
              {empty && (
                <div className="border-border text-ink-muted flex flex-wrap items-center justify-between gap-3 rounded-lg border border-dashed px-4 py-3 text-sm">
                  <span>
                    Nothing booked{' '}
                    {view === 'day'
                      ? `on ${formatIsoDate(date)}`
                      : `between ${formatIsoDate(data.range.from)} and ${formatIsoDate(data.range.to)}`}
                    .
                  </span>
                  <Button variant="secondary" size="sm" onClick={() => book(null, date)}>
                    Book something
                  </Button>
                </div>
              )}

              {view === 'day' ? (
                <DayView
                  date={date}
                  calendar={data}
                  employees={employees}
                  openingHours={openingHours.get(isoDateWeekday(date)) ?? []}
                  now={now}
                  onOpen={setOpened}
                  onPick={(employeeId) => book(employeeId, date)}
                />
              ) : (
                <WeekView
                  calendar={data}
                  openingHours={openingHours}
                  now={now}
                  onOpen={setOpened}
                  onPick={(day) => book(null, day)}
                />
              )}

              <p className="text-ink-muted text-xs">
                Times are in {data.range.timezone}, wherever you are reading this.
              </p>
            </div>
          );
        }}
      </ResourceGate>

      {opened && <DetailDrawer appointmentId={opened.id} onClose={() => setOpened(null)} />}
    </>
  );
}
