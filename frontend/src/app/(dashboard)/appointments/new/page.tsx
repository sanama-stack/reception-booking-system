'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { SlotPicker } from '@/components/slot-picker';
import {
  Button,
  ButtonLink,
  Card,
  CardHeader,
  EmptyState,
  Input,
  ResourceGate,
  Select,
  Textarea,
  useToast,
} from '@/components/ui';
import { ApiError, type ErrorCode } from '@/lib/api/client';
import { appointmentApi } from '@/lib/appointments';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import { formatDuration, type ServiceDetail, type ServiceList } from '@/lib/catalog';
import { availabilityPath, type AvailableSlot } from '@/lib/scheduling';
import type { EmployeeList } from '@/lib/staff';
import { formatMoney, formatTime, toBusinessDate, type Timezone } from '@/lib/time';

/**
 * Every refusal that means **the slot list on screen is out of date**.
 *
 * `SLOT_UNAVAILABLE` is the one the phase document singles out — the exclusion constraint refused
 * the write because somebody else took the time between this list being drawn and the button being
 * pressed — but it is not the only way the world moves underneath an open screen. A service
 * deactivated in another tab, an employee unassigned, or simply enough time passing for the start
 * to fall inside the minimum lead time all leave a list of times that can no longer be booked.
 *
 * All of them get the same treatment, because the same thing is true of all of them: the answer on
 * screen was computed against a world that has changed, so it is re-asked and the selection is
 * dropped. Silently leaving the old times up — or leaving one selected — would invite the owner to
 * press the same doomed button again.
 *
 * A validation failure is deliberately **not** here. A mistyped phone number says nothing about
 * availability, and clearing a chosen time because a name was too long would be its own defect.
 */
const STALE_SLOT_CODES: ReadonlySet<ErrorCode> = new Set<ErrorCode>([
  'SLOT_UNAVAILABLE',
  'SERVICE_INACTIVE',
  'EMPLOYEE_INACTIVE',
  'EMPLOYEE_CANNOT_PERFORM_SERVICE',
  'OUTSIDE_BUSINESS_HOURS',
  'OUTSIDE_WORKING_HOURS',
  'BOOKING_IN_PAST',
  'BELOW_MIN_LEAD_TIME',
  'BEYOND_MAX_ADVANCE',
]);

interface Selection {
  startsAt: string;
  employeeId: string;
  employeeName: string;
  /** The zone the chosen time was rendered in, so the summary cannot drift from the picker. */
  timezone: Timezone;
}

/**
 * Booking from the dashboard: service → who → day → time → who it is for.
 *
 * The order is the order the answers narrow each other. Service first because it decides how long
 * the appointment is and who can perform it; the person and the day next because they are the two
 * halves of the question the engine answers; the customer last because their details change
 * nothing about which times exist.
 *
 * **Nothing here recomputes availability itself.** The times come from `GET /availability`, the
 * same read the employee preview uses and the same one phase 08's public page will use, and the
 * chosen Slot carries the Employee it will be booked with. A screen that resolved a person of its
 * own would be a second opinion about a question already answered.
 */
export default function NewAppointmentPage() {
  const router = useRouter();
  const toast = useToast();
  const { session } = useSession();
  const services = useResource<ServiceList>('/services');
  const employees = useResource<EmployeeList>('/employees');

  if (!session) return null;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/appointments" className="text-ink-muted text-sm hover:underline">
          ← Appointments
        </Link>
      </div>

      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">New appointment</h1>
        <p className="text-ink-muted mt-1 text-sm">
          The same times a customer would be offered, booked against the same rules.
        </p>
      </div>

      <ResourceGate resource={services}>
        {(catalogue) => (
          <ResourceGate resource={employees}>
            {(staff) =>
              catalogue.services.length === 0 ? (
                <EmptyState
                  title="No services to book"
                  description="An appointment is a service, performed by a person, at a time. Add a service first and this screen has something to offer."
                  action={<ButtonLink href="/services/new">Add a service</ButtonLink>}
                />
              ) : (
                <BookingFlow
                  services={catalogue.services}
                  employees={staff}
                  timezone={session.business.timezone}
                  onBooked={(id, name) => {
                    toast(`Booked for ${name}.`, 'success');
                    router.push(`/appointments/${id}`);
                  }}
                  onFailed={(message) => toast(message, 'error')}
                />
              )
            }
          </ResourceGate>
        )}
      </ResourceGate>
    </div>
  );
}

function BookingFlow({
  services,
  employees,
  timezone,
  onBooked,
  onFailed,
}: {
  services: ServiceDetail[];
  employees: EmployeeList;
  timezone: Timezone;
  onBooked: (id: string, customerName: string) => void;
  onFailed: (message: string) => void;
}) {
  const [serviceId, setServiceId] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [date, setDate] = useState(() => toBusinessDate(new Date(), timezone));
  const [selection, setSelection] = useState<Selection | null>(null);
  const [refreshes, setRefreshes] = useState(0);

  const [customerName, setCustomerName] = useState('');
  const [customerPhone, setCustomerPhone] = useState('');
  const [customerEmail, setCustomerEmail] = useState('');
  const [customerNote, setCustomerNote] = useState('');

  const [booking, setBooking] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Resolved on every render rather than stored, so a service deactivated or an assignment removed
  // in another tab cannot leave a picker naming a choice the server would refuse — the phase-05
  // rule, and the reason there is one branch here rather than two.
  const service = services.find((candidate) => candidate.id === serviceId) ?? services[0];
  const eligible = service
    ? employees.employees.filter((employee) => service.employeeIds.includes(employee.id))
    : [];
  const employee = eligible.find((candidate) => candidate.id === employeeId) ?? null;

  /** Any change to the question invalidates an answer taken from the old one. */
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

  async function onBook(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!service || !selection) return;

    setBooking(true);
    setError(null);
    try {
      const booked = await appointmentApi.book({
        serviceId: service.id,
        // Off the Slot, never re-resolved. With "anyone" selected this is the person the owner was
        // actually shown, which is the only one they can be said to have agreed to.
        employeeId: selection.employeeId,
        startsAt: selection.startsAt,
        customerName: customerName.trim(),
        // Left exactly as typed. The server normalises it to E.164 against the business's country,
        // and trimming here would be the first step of a second normaliser — which is how one
        // person becomes two customers with two histories.
        customerPhone,
        ...(customerEmail.trim() ? { customerEmail: customerEmail.trim() } : {}),
        ...(customerNote.trim() ? { customerNote: customerNote.trim() } : {}),
      });
      onBooked(booked.appointment.id, booked.appointment.customer.fullName);
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        // Nothing reaches here through the client, which wraps even a dead connection as a
        // `NETWORK_ERROR`. It is handled anyway because the one thing a booking must never do is
        // fail without saying so.
        setError(null);
        onFailed('The appointment could not be booked.');
        return;
      }
      setError(cause);
      if (STALE_SLOT_CODES.has(cause.code)) {
        setSelection(null);
        setRefreshes((count) => count + 1);
      }
    } finally {
      setBooking(false);
    }
  }

  if (!service) return null;

  const fieldErrors = error?.fieldErrors ?? {};
  const rendered = new Set(['customerName', 'customerPhone', 'customerEmail', 'customerNote']);
  const everyMessageShown =
    error !== null &&
    Object.keys(fieldErrors).length > 0 &&
    Object.keys(fieldErrors).every((field) => rendered.has(field));
  const unfielded = error && !everyMessageShown ? error.message : null;

  return (
    <form onSubmit={onBook} className="flex flex-col gap-6" noValidate>
      <Card>
        <CardHeader
          title="What, and with whom"
          description="Only people assigned to the service can perform it, so the list changes with the service."
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Select
            label="Service"
            value={service.id}
            onChange={(event) => ask(() => setServiceId(event.target.value))}
          >
            {services.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {serviceLabel(candidate)}
              </option>
            ))}
          </Select>
          <Select
            label="Employee"
            value={employee?.id ?? ''}
            disabled={eligible.length === 0}
            onChange={(event) => ask(() => setEmployeeId(event.target.value))}
            hint={
              eligible.length === 0
                ? 'Nobody is assigned to this service yet.'
                : 'Anyone offers every eligible person’s times at once.'
            }
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
      </Card>

      <Card>
        <CardHeader
          title="When"
          description={`${formatDuration(service.durationMinutes)}, ${formatMoney(service.price.amount, service.price.currency)}. Times are in ${timezone}.`}
        />
        <Input
          label="Date"
          type="date"
          value={date}
          onChange={(event) => ask(() => setDate(event.target.value))}
        />

        <div className="mt-5">
          {path ? (
            <SlotPicker
              key={`${path}#${refreshes}`}
              path={path}
              service={service}
              employeeName={employee?.fullName ?? null}
              selectedStartsAt={selection?.startsAt ?? null}
              onSelect={(slot: AvailableSlot, slotTimezone) =>
                setSelection({
                  startsAt: slot.startsAt,
                  employeeId: slot.employee.id,
                  employeeName: slot.employee.fullName,
                  timezone: slotTimezone,
                })
              }
            />
          ) : (
            <EmptyState title="Pick a date" description="Times are computed one day at a time." />
          )}
        </div>
      </Card>

      <Card>
        <CardHeader
          title="Who it is for"
          description="The phone number identifies the customer. Booking under a number you already have adds to that person’s history rather than starting a second one."
        />
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="Full name"
              value={customerName}
              maxLength={120}
              required
              onChange={(event) => setCustomerName(event.target.value)}
              error={fieldErrors.customerName}
            />
            <Input
              label="Phone"
              type="tel"
              value={customerPhone}
              maxLength={30}
              required
              onChange={(event) => setCustomerPhone(event.target.value)}
              hint="A local number works once your country is set under Settings; otherwise start with +."
              error={fieldErrors.customerPhone}
            />
          </div>
          <Input
            label="Email"
            type="email"
            value={customerEmail}
            maxLength={254}
            onChange={(event) => setCustomerEmail(event.target.value)}
            hint="Optional — where the confirmation will go once notifications are live."
            error={fieldErrors.customerEmail}
          />
          <Textarea
            label="Note"
            value={customerNote}
            maxLength={1000}
            rows={3}
            onChange={(event) => setCustomerNote(event.target.value)}
            hint="Optional. Anything the person performing it should know."
            error={fieldErrors.customerNote}
          />
        </div>
      </Card>

      {unfielded && (
        <p
          role="alert"
          className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
        >
          {unfielded}
          {error && STALE_SLOT_CODES.has(error.code) && (
            <span className="mt-1 block">
              The times above have been recalculated — choose one of those.
            </span>
          )}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <Button
          type="submit"
          loading={booking}
          disabled={!selection || customerName.trim() === '' || customerPhone.trim() === ''}
        >
          Book appointment
        </Button>
        <ButtonLink href="/appointments" variant="secondary">
          Cancel
        </ButtonLink>
        {selection && (
          <p className="text-ink-muted text-sm">
            {formatTime(selection.startsAt, selection.timezone)} with {selection.employeeName}
          </p>
        )}
      </div>
    </form>
  );
}

/** Inactive services are marked rather than hidden — the phase-05 rule, and the same reason. */
function serviceLabel(service: ServiceDetail): string {
  const duration = formatDuration(service.durationMinutes);
  return service.active
    ? `${service.name} · ${duration}`
    : `${service.name} · ${duration} · not offered`;
}
