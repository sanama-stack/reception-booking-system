'use client';

import { explainEmptyReason } from '@/components/empty-reason';
import { EmptyState, ResourceGate, cn } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { DAYS } from '@/lib/business';
import type { PublicService } from '@/lib/public';
import type { Availability, AvailableSlot } from '@/lib/scheduling';
import { formatIsoDate, formatTime, isoDateWeekday, type IsoDate } from '@/lib/time';

/**
 * A fortnight of availability: which days have times, and the times on the chosen day.
 *
 * **One request for the whole window**, which is what the phase-05 preview meant by "phase 08's
 * public page is where the range earns its keep". A Customer poking at one date at a time has no
 * way to tell a closed Monday from a full one without trying it, and a business that is booked
 * solid for a week reads as a broken page. A strip showing where the times are turns that into a
 * choice.
 *
 * Keyed on its own question by the caller, so a new question remounts it. `useResource` keeps the
 * data it last loaded when a later load fails, which is right for a screen reloading one resource
 * and wrong here: the previous answer is about another service, another person or another fortnight,
 * and leaving it under a changed picker would offer a time that was never computed for what is now
 * selected. On this page that is not a stale display but a wrong *booking*.
 */
export function AvailabilityStep({
  path,
  service,
  employeeName,
  chosenDate,
  onChooseDate,
  selectedStartsAt,
  onSelectSlot,
}: {
  path: string;
  service: PublicService;
  /**
   * The Employee the Customer named, or `null` for "anyone who can perform this".
   *
   * Used only to word an empty result — "Dana is not working" and "nobody is working" are
   * different facts. It deliberately does **not** decide whether each Slot is labelled with a
   * name; see `manyPeople` below.
   */
  employeeName: string | null;
  chosenDate: IsoDate | null;
  onChooseDate: (date: IsoDate) => void;
  selectedStartsAt: string | null;
  onSelectSlot: (slot: AvailableSlot, timezone: string) => void;
}) {
  const availability = useResource<Availability>(path);

  return (
    <ResourceGate resource={availability}>
      {(result) => {
        const total = result.days.reduce((count, day) => count + day.slots.length, 0);

        if (total === 0) {
          const reason = explainEmptyReason(result.emptyReason, {
            audience: 'customer',
            serviceName: service.name,
            durationMinutes: service.durationMinutes,
            employeeName,
          });
          // No `emptyReason` code printed beside it, unlike the owner's screens. The enum name is
          // useful to somebody reading the engine and is noise to somebody booking a haircut.
          return <EmptyState title={reason.title} description={reason.description} />;
        }

        /**
         * The day whose times are shown.
         *
         * The Customer's choice when this window still holds it and it still has times, and
         * otherwise the soonest day that does. That fallback is what makes paging and changing the
         * service work without an effect to resynchronise them: a chosen date from the previous
         * fortnight simply is not found, and the soonest available day is a better landing place
         * than an empty one.
         */
        const active =
          result.days.find((day) => day.date === chosenDate && day.slots.length > 0) ??
          result.days.find((day) => day.slots.length > 0) ??
          null;

        /**
         * Whether a name under each time tells the Customer anything.
         *
         * Derived from the Slots on screen rather than from what was selected, because "did I
         * name somebody" and "is there more than one answer" come apart in both directions. A
         * Service only one person provides hides its picker — nothing to choose — so the request
         * still carries no `employeeId`, and labelling from *that* would stamp the same name under
         * all twenty-nine buttons. A Customer who did name somebody gets one id back, so the same
         * rule suppresses the name there too, with no second condition to keep in step.
         */
        const manyPeople =
          active === null ? false : new Set(active.slots.map((slot) => slot.employee.id)).size > 1;

        return (
          <div className="flex flex-col gap-5">
            <ul className="grid grid-cols-4 gap-2 sm:grid-cols-7">
              {result.days.map((day) => {
                const count = day.slots.length;
                const isActive = active?.date === day.date;
                const weekday = DAYS.find((entry) => entry.value === isoDateWeekday(day.date));
                return (
                  <li key={day.date}>
                    <button
                      type="button"
                      disabled={count === 0}
                      aria-pressed={isActive}
                      // The visible label is three terse lines, so the whole fact goes in the
                      // accessible name: a screen reader would otherwise announce "Tue 9 6".
                      aria-label={`${formatIsoDate(day.date)}, ${
                        count === 0 ? 'no times' : `${count} ${count === 1 ? 'time' : 'times'}`
                      }`}
                      onClick={() => onChooseDate(day.date)}
                      className={cn(
                        'flex min-h-16 w-full flex-col items-center justify-center rounded-md border px-1 py-2 transition',
                        'disabled:cursor-not-allowed disabled:opacity-45',
                        isActive
                          ? 'border-brand bg-brand text-brand-contrast'
                          : 'border-border bg-surface text-ink enabled:hover:bg-surface-muted',
                      )}
                    >
                      <span className="text-xs">{weekday?.short ?? ''}</span>
                      <span className="text-base font-medium tabular-nums">
                        {Number(day.date.slice(8, 10))}
                      </span>
                      <span
                        aria-hidden
                        className={cn(
                          'text-xs tabular-nums',
                          isActive ? 'text-brand-contrast/80' : 'text-ink-muted',
                        )}
                      >
                        {count === 0 ? '—' : count}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>

            {active && (
              <div className="flex flex-col gap-3">
                <p className="text-ink text-sm font-medium">{formatIsoDate(active.date)}</p>
                <ul className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                  {active.slots.map((slot) => {
                    const selected = slot.startsAt === selectedStartsAt;
                    return (
                      <li key={`${slot.startsAt}-${slot.employee.id}`}>
                        <button
                          type="button"
                          aria-pressed={selected}
                          onClick={() => onSelectSlot(slot, result.timezone)}
                          // `min-h-11` is 44 px, the smallest target a thumb hits reliably, and
                          // the reason this grid is three columns at 360 px rather than four.
                          className={cn(
                            'min-h-11 w-full rounded-md border px-2 py-2 text-sm tabular-nums transition',
                            selected
                              ? 'border-brand bg-brand text-brand-contrast font-medium'
                              : 'border-border bg-surface text-ink hover:bg-surface-muted',
                          )}
                        >
                          {formatTime(slot.startsAt, result.timezone)}
                          {/*
                            Who would perform it, but only where that varies between the times on
                            screen. The same name on every button says nothing, and repeating it
                            thirty times says it thirty times.
                          */}
                          {manyPeople && (
                            <span
                              className={cn(
                                'mt-0.5 block text-xs font-normal',
                                selected ? 'text-brand-contrast/80' : 'text-ink-muted',
                              )}
                            >
                              {slot.employee.fullName}
                            </span>
                          )}
                        </button>
                      </li>
                    );
                  })}
                </ul>
                <p className="text-ink-muted text-xs">
                  All times in {result.timezone}, the business’s own clock.
                </p>
              </div>
            )}
          </div>
        );
      }}
    </ResourceGate>
  );
}
