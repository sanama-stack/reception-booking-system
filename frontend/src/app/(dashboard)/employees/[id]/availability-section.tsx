'use client';

import { useState } from 'react';
import { explainEmptyReason } from '@/components/empty-reason';
import { Button, Card, CardHeader, EmptyState, Input, ResourceGate, Select } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { formatDuration, type ServiceDetail } from '@/lib/catalog';
import { availabilityPath, type Availability } from '@/lib/scheduling';
import type { EmployeeDetail } from '@/lib/staff';
import { formatIsoDate, formatTimeRange, toBusinessDate, type Timezone } from '@/lib/time';

/**
 * What the availability engine actually answers for this person, on one day.
 *
 * The fifth section on this page and the only one that reads rather than writes. Four editors sit
 * above it — who they are, what they provide, when they work, when they are away — and every one
 * of them changes what this computes. Showing the result underneath them is what turns four
 * separate forms into one answerable question: *can this person be booked, and when?*
 *
 * One date at a time, deliberately. The endpoint takes a range of up to 31 days, but `emptyReason`
 * describes the whole result rather than each day in it, so a single date is the shape in which a
 * reason means exactly one thing. Phase 08's public page is where the range earns its keep.
 */
export function AvailabilitySection({
  employee,
  services,
  timezone,
}: {
  employee: EmployeeDetail;
  services: ServiceDetail[];
  timezone: Timezone;
}) {
  const [serviceId, setServiceId] = useState('');
  const [date, setDate] = useState(() => toBusinessDate(new Date(), timezone));
  const [recalculations, setRecalculations] = useState(0);

  // Resolved rather than stored, so an assignment saved in the section above cannot leave this
  // picker naming a service the employee no longer provides — which the server would refuse with
  // `EMPLOYEE_CANNOT_PERFORM_SERVICE` for a choice the owner did not make.
  const assigned = services.filter((service) => employee.serviceIds.includes(service.id));
  const selected = assigned.find((service) => service.id === serviceId) ?? assigned[0];

  const path =
    selected && date
      ? availabilityPath({ serviceId: selected.id, from: date, to: date, employeeId: employee.id })
      : null;

  return (
    <Card>
      <CardHeader
        title="Availability preview"
        description={`When ${employee.fullName} could actually be booked: your opening hours and their working schedule where the two overlap, less their time off, your closures and anything already on the calendar.`}
      />

      {!selected ? (
        <EmptyState
          title="Nothing to preview yet"
          description={`${employee.fullName} is not assigned to any service, so there is nothing to compute. Tick one under “What they provide” above and save it.`}
        />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_auto] sm:items-start">
            <Select
              label="Service"
              value={selected.id}
              onChange={(event) => setServiceId(event.target.value)}
            >
              {assigned.map((service) => (
                <option key={service.id} value={service.id}>
                  {optionLabel(service)}
                </option>
              ))}
            </Select>
            <Input
              label="Date"
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
            {/* The four editors above change the answer without changing the question, so a
                schedule saved a moment ago would otherwise need the date typed twice to be seen. */}
            <Button
              variant="secondary"
              className="sm:mt-7"
              onClick={() => setRecalculations((count) => count + 1)}
            >
              Recalculate
            </Button>
          </div>

          <div className="mt-6">
            {path ? (
              <Slots
                key={`${path}#${recalculations}`}
                path={path}
                service={selected}
                employeeName={employee.fullName}
              />
            ) : (
              <EmptyState
                title="Pick a date"
                description="Availability is computed one day at a time."
              />
            )}
          </div>
        </>
      )}
    </Card>
  );
}

/**
 * Keyed on its own question by the caller, so a new one starts from nothing.
 *
 * `useResource` keeps the data it last loaded when a later load fails, which is right for a screen
 * reloading one resource and wrong here: the previous answer is about another service or another
 * day, and leaving it under a changed picker would state something false. A remount makes "this is
 * a new question" the only thing the component can say — and it is what puts the loading and error
 * states back on screen instead of hiding them behind a stale success.
 */
function Slots({
  path,
  service,
  employeeName,
}: {
  path: string;
  service: ServiceDetail;
  employeeName: string;
}) {
  const availability = useResource<Availability>(path);

  return (
    <ResourceGate resource={availability}>
      {(result) => {
        // One date is asked for at a time, so `days` holds exactly one. Rendering the list rather
        // than reaching for `[0]` keeps this honest about the shape phase 08 will use in full.
        const total = result.days.reduce((count, day) => count + day.slots.length, 0);

        if (total === 0) {
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
              {/* The reason itself, for whoever is reading this as a preview of the engine rather
                  than as an answer about their week. */}
              <p className="text-ink-muted text-center font-mono text-xs">
                {result.emptyReason ?? 'no reason given'}
              </p>
            </div>
          );
        }

        return (
          <div className="flex flex-col gap-4">
            {result.days.map((day) => (
              <div key={day.date} className="flex flex-col gap-2">
                <p className="text-ink-muted text-sm">
                  <span className="text-ink font-medium">{formatIsoDate(day.date)}</span> —{' '}
                  {day.slots.length} {day.slots.length === 1 ? 'time' : 'times'} for {service.name}{' '}
                  ({formatDuration(service.durationMinutes)}), in {result.timezone}.
                </p>
                <ul className="flex flex-wrap gap-2">
                  {day.slots.map((slot) => (
                    <li
                      key={`${slot.startsAt}-${slot.employee.id}`}
                      className="border-border bg-surface-muted text-ink rounded-md border px-2.5 py-1.5 text-sm tabular-nums"
                    >
                      {formatTimeRange(slot.startsAt, slot.endsAt, result.timezone)}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        );
      }}
    </ResourceGate>
  );
}

function optionLabel(service: ServiceDetail): string {
  const duration = formatDuration(service.durationMinutes);
  return service.active
    ? `${service.name} · ${duration}`
    : `${service.name} · ${duration} · not offered`;
}
