'use client';

import { explainEmptyReason } from '@/components/empty-reason';
import { EmptyState, ResourceGate, cn } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { ServiceDetail } from '@/lib/catalog';
import type { Availability, AvailableSlot } from '@/lib/scheduling';
import { formatTime } from '@/lib/time';

/**
 * The times that can actually be booked, keyed on its own question by the caller.
 *
 * Same shape and same reason as the availability preview's `Slots`: a new question remounts this,
 * so a stale answer can never sit under a changed picker (the phase-05 frontend handoff §4.2). It
 * matters more here than there. On the preview a stale list is a wrong answer; here it is a wrong
 * *booking* — clicking 14:00 under a service the owner changed a second ago would send the previous
 * service's slot to a request carrying the new service's id.
 *
 * The Employee comes off the Slot and is never re-resolved. Every slot already names who would
 * perform it, and resolving again at booking time would be a second chance to answer differently
 * from what was shown — which, with "anyone" selected, is a genuinely different person.
 */
export function SlotPicker({
  path,
  service,
  employeeName,
  selectedStartsAt,
  onSelect,
}: {
  path: string;
  service: ServiceDetail;
  /** `null` when the owner asked for anyone who can perform the service. */
  employeeName: string | null;
  selectedStartsAt: string | null;
  onSelect: (slot: AvailableSlot, timezone: string) => void;
}) {
  const availability = useResource<Availability>(path);

  return (
    <ResourceGate resource={availability}>
      {(result) => {
        // One date is asked for at a time, so `days` holds exactly one. Rendering the list rather
        // than reaching for `[0]` keeps this honest about the shape phase 08 will use in full.
        const slots = result.days.flatMap((day) => day.slots);

        if (slots.length === 0) {
          const reason = explainEmptyReason(result.emptyReason, {
            serviceName: service.name,
            durationMinutes: service.durationMinutes,
            employeeName,
          });
          return (
            <div className="flex flex-col gap-2">
              <EmptyState
                title={reason.title}
                description={reason.description}
                action={reason.action}
              />
              <p className="text-ink-muted text-center font-mono text-xs">
                {result.emptyReason ?? 'no reason given'}
              </p>
            </div>
          );
        }

        return (
          <div className="flex flex-col gap-3">
            <ul className="flex flex-wrap gap-2">
              {slots.map((slot) => {
                const selected = slot.startsAt === selectedStartsAt;
                return (
                  <li key={`${slot.startsAt}-${slot.employee.id}`}>
                    <button
                      type="button"
                      aria-pressed={selected}
                      onClick={() => onSelect(slot, result.timezone)}
                      className={cn(
                        'rounded-md border px-3 py-2 text-sm tabular-nums transition',
                        selected
                          ? 'border-brand bg-brand text-brand-contrast font-medium'
                          : 'border-border bg-surface text-ink hover:bg-surface-muted',
                      )}
                    >
                      {formatTime(slot.startsAt, result.timezone)}
                      {/*
                        Who would perform it, but only when the owner did not pick a person — with
                        one selected it is the same name on every button, and repeating it thirty
                        times says nothing.
                      */}
                      {employeeName === null && (
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
              {slots.length} {slots.length === 1 ? 'time' : 'times'}, in {result.timezone}.
            </p>
          </div>
        );
      }}
    </ResourceGate>
  );
}
