'use client';

import { useState } from 'react';
import { SlotPicker } from '@/components/slot-picker';
import { Button, Card, CardHeader, EmptyState, Input, Select, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import {
  appointmentApi,
  type AppointmentDetail,
  type AppointmentWithHistory,
} from '@/lib/appointments';
import type { ServiceDetail } from '@/lib/catalog';
import { availabilityPath, type AvailableSlot } from '@/lib/scheduling';
import type { EmployeeList } from '@/lib/staff';
import { formatDateTime, formatTime, toBusinessDate, type Timezone } from '@/lib/time';

/**
 * Moving an appointment: same record, same Confirmation Code, different time.
 *
 * A reschedule is an **update**, not a cancel-and-rebook, and the difference is not cosmetic:
 * cancelling first would put the original slot back on sale for as long as it took to write the new
 * one, and a concurrent booking taking it would leave the customer with nothing at all.
 *
 * A day at a time, from the same `GET /availability` the booking flow reads. Two `409`s are
 * possible and they are different failures — `SLOT_UNAVAILABLE` is the new time being taken, and
 * `VERSION_CONFLICT` is this appointment having moved under the screen.
 */
export function RescheduleSection({
  appointment,
  service,
  employees,
  timezone,
  onUpdated,
  onReload,
  onDone,
}: {
  appointment: AppointmentDetail;
  /** `null` if the service has since been deleted, which leaves nothing to compute a length from. */
  service: ServiceDetail | null;
  employees: EmployeeList;
  timezone: Timezone;
  onUpdated: (next: AppointmentWithHistory) => void;
  onReload: () => Promise<void>;
  onDone: () => void;
}) {
  const toast = useToast();
  const [date, setDate] = useState(() => toBusinessDate(appointment.startsAt, timezone));
  const [employeeId, setEmployeeId] = useState(appointment.employee.id);
  const [selection, setSelection] = useState<AvailableSlot | null>(null);
  const [refreshes, setRefreshes] = useState(0);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const eligible = service
    ? employees.employees.filter((employee) => service.employeeIds.includes(employee.id))
    : [];
  const employee = eligible.find((candidate) => candidate.id === employeeId) ?? null;

  function ask(change: () => void) {
    change();
    setSelection(null);
    setError(null);
  }

  const path =
    service && date
      ? availabilityPath({
          serviceId: service.id,
          from: date,
          to: date,
          ...(employee ? { employeeId: employee.id } : {}),
        })
      : null;

  async function onMove() {
    if (!selection) return;
    setSaving(true);
    setError(null);
    try {
      onUpdated(
        await appointmentApi.reschedule(appointment.id, {
          startsAt: selection.startsAt,
          // Sent even when unchanged. The server treats absent as "keep the current person", and
          // being explicit costs nothing while making a move to somebody else the same call.
          employeeId: selection.employee.id,
        }),
      );
      onDone();
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        // Unreachable through the client, which wraps even a dead connection. Answered anyway,
        // because a move that neither succeeds nor complains is the worst of the three outcomes.
        toast('The appointment could not be moved.', 'error');
        return;
      }
      setError(cause);
      if (cause.code === 'VERSION_CONFLICT') {
        await onReload();
        onDone();
        return;
      }
      // Every other refusal means the times on screen were computed against a world that has
      // since moved — most obviously `SLOT_UNAVAILABLE`, where the constraint refused the write
      // because somebody took the slot first. Re-ask rather than leaving a dead list up.
      setSelection(null);
      setRefreshes((count) => count + 1);
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <CardHeader
        title="Move this appointment"
        description={`Currently ${formatDateTime(appointment.startsAt, timezone)} with ${appointment.employee.name}. The Confirmation Code does not change.`}
      />

      {!service ? (
        <EmptyState
          title="This service no longer exists"
          description="Availability is computed from the service’s length, so there is nothing to offer. Cancel and rebook under a service you still provide."
        />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="New date"
              type="date"
              value={date}
              onChange={(event) => ask(() => setDate(event.target.value))}
            />
            <Select
              label="With"
              value={employee?.id ?? ''}
              onChange={(event) => ask(() => setEmployeeId(event.target.value))}
              hint="Moving to a different person is the same operation."
            >
              <option value="">Anyone who can</option>
              {eligible.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.fullName}
                  {candidate.active ? '' : ' · inactive'}
                </option>
              ))}
            </Select>
          </div>

          {/*
            `GET /availability` has no way to exclude the appointment being moved, so this list
            counts the appointment's own current time against itself and leaves it out. The move
            still works — the exclusion constraint never compares a row with itself — but the slot
            it is sitting in is not offered back, which would otherwise look like a defect. Phase 08
            needs the same exclusion for a customer moving their own booking through a Manage Link,
            and owns the query parameter that fixes both.
          */}
          <p className="text-ink-muted mt-3 text-xs">
            The appointment’s own current time is not in this list — it is still holding that slot.
          </p>

          <div className="mt-5">
            {path ? (
              <SlotPicker
                key={`${path}#${refreshes}`}
                path={path}
                service={service}
                employeeName={employee?.fullName ?? null}
                selectedStartsAt={selection?.startsAt ?? null}
                onSelect={(slot) => setSelection(slot)}
              />
            ) : (
              <EmptyState title="Pick a date" description="Times are computed one day at a time." />
            )}
          </div>

          {error && (
            <p
              role="alert"
              className="border-danger/30 bg-danger/5 text-ink mt-4 rounded-md border px-3 py-2 text-sm"
            >
              {error.message}
              {error.code !== 'VERSION_CONFLICT' && (
                <span className="mt-1 block">
                  The times above have been recalculated — choose one of those.
                </span>
              )}
            </p>
          )}

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <Button loading={saving} disabled={!selection} onClick={() => void onMove()}>
              Move appointment
            </Button>
            <Button variant="secondary" onClick={onDone} disabled={saving}>
              Cancel
            </Button>
            {selection && (
              <p className="text-ink-muted text-sm">
                Moving to {formatTime(selection.startsAt, timezone)} with{' '}
                {selection.employee.fullName}
              </p>
            )}
          </div>
        </>
      )}
    </Card>
  );
}
