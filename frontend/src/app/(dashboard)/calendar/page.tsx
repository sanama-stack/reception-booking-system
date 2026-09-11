'use client';

import { useState } from 'react';
import { Button, ButtonLink, Card, Input, ResourceGate, cn } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import type { WeekHours } from '@/lib/business';
import { calendarPath } from '@/lib/calendar';
import type { EmployeeList } from '@/lib/staff';
import { addIsoDays, formatIsoDate, toBusinessDate, type IsoDate } from '@/lib/time';
import { CalendarScreen } from './calendar-screen';
import { wallClockSpan, weekOf, type Span } from './geometry';

type View = 'day' | 'week';

/**
 * The calendar.
 *
 * **Three reads, and only one of them is the view.** `GET /calendar` answers what is on the grid
 * and is re-read on every navigation, because a booking taken in another tab has to appear. The
 * other two — the opening hours and the roster — are configuration: they say what the grid's hours
 * are and who has a column, they do not change when the date does, and re-fetching them on every
 * arrow press would be three requests to move one day.
 *
 * That is not a breach of the phase's "one request per view, no per-day fetching". The rule exists
 * because *the view's contents* must be one moment — appointments, closures and time off fetched
 * separately would render a booking made between the first call and the last as a block with no
 * employee. Configuration read once has no such seam.
 */
export default function CalendarPage() {
  const { session } = useSession();
  const hours = useResource<WeekHours>('/business/hours');
  const employees = useResource<EmployeeList>('/employees');

  const timezone = session?.business.timezone;
  const [view, setView] = useState<View>('day');
  // Today in the *business's* zone, not the browser's. An owner in Berlin opening this at 23:30
  // should land on the day their business is in, which is already tomorrow (ADR-0003).
  const [date, setDate] = useState<IsoDate | null>(null);

  if (!session || !timezone) return null;

  const anchor = date ?? toBusinessDate(new Date(), timezone);
  const range = view === 'day' ? { from: anchor, to: anchor } : weekOf(anchor);
  const path = calendarPath(range.from, range.to);
  const step = view === 'day' ? 1 : 7;

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-ink text-2xl font-semibold tracking-tight">Calendar</h1>
          <p className="text-ink-muted mt-1 text-sm">
            {view === 'day'
              ? formatIsoDate(anchor)
              : `${formatIsoDate(range.from)} – ${formatIsoDate(range.to)}`}
          </p>
        </div>
        <ButtonLink href="/appointments/new">New appointment</ButtonLink>
      </div>

      <Card className="p-4">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="flex flex-wrap items-end gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setDate(addIsoDays(anchor, -step))}
            >
              ← Previous
            </Button>
            <Button variant="secondary" size="sm" onClick={() => setDate(null)}>
              Today
            </Button>
            <Button variant="secondary" size="sm" onClick={() => setDate(addIsoDays(anchor, step))}>
              Next →
            </Button>
            <Input
              label="Date"
              type="date"
              value={anchor}
              onChange={(event) => setDate(event.target.value || null)}
            />
          </div>

          <div
            role="group"
            aria-label="View"
            className="border-border bg-surface-muted inline-flex rounded-md border p-0.5"
          >
            {(['day', 'week'] as View[]).map((candidate) => (
              <button
                key={candidate}
                type="button"
                aria-pressed={view === candidate}
                onClick={() => setView(candidate)}
                className={cn(
                  'rounded px-3 py-1.5 text-sm capitalize',
                  view === candidate
                    ? 'bg-surface text-ink font-medium shadow-sm'
                    : 'text-ink-muted hover:text-ink',
                )}
              >
                {candidate}
              </button>
            ))}
          </div>
        </div>
      </Card>

      <ResourceGate resource={hours}>
        {(week) => (
          <ResourceGate resource={employees}>
            {(roster) => (
              <CalendarScreen
                key={path}
                path={path}
                view={view}
                date={anchor}
                employees={roster.employees}
                openingHours={openingHoursByWeekday(week)}
              />
            )}
          </ResourceGate>
        )}
      </ResourceGate>
    </div>
  );
}

/**
 * The week's hours, indexed by ISO weekday.
 *
 * A `Map` rather than an array because a closed day is genuinely absent from the response — the
 * hours endpoint sends the intervals that exist, and "Sunday has no entry" is how it says the
 * business is shut. An array of seven would have to invent something for the empty ones.
 */
function openingHoursByWeekday(week: WeekHours): Map<number, Span[]> {
  const byDay = new Map<number, Span[]>();
  for (const day of week.hours) {
    const spans = byDay.get(day.dayOfWeek) ?? [];
    spans.push(wallClockSpan(day.opensAt, day.closesAt));
    byDay.set(day.dayOfWeek, spans);
  }
  return byDay;
}
