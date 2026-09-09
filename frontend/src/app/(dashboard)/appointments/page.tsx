'use client';

import { useState } from 'react';
import { Button, ButtonLink, Card, Input, ResourceGate, Select } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { appointmentsPath, type AppointmentStatus } from '@/lib/appointments';
import type { EmployeeList } from '@/lib/staff';
import { AppointmentsScreen } from './appointments-screen';

const STATUSES: AppointmentStatus[] = ['CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED'];

const STATUS_LABELS: Record<AppointmentStatus, string> = {
  CONFIRMED: 'Confirmed',
  COMPLETED: 'Completed',
  NO_SHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

interface Filters {
  from: string;
  to: string;
  status: '' | AppointmentStatus;
  employeeId: string;
}

const NO_FILTERS: Filters = { from: '', to: '', status: '', employeeId: '' };

/**
 * Everything booked, filterable four ways.
 *
 * Oldest first, which is the server's order and the right one here: this is the list an owner works
 * down on the morning of, not a feed. A customer's profile reverses it, because that is read as a
 * history.
 *
 * **The filters and the page number are one question, and the question is a path.** Changing a
 * filter while looking at page 4 of the previous question would ask for page 4 of a result that may
 * have one page, so every filter change resets to the first — the alternative is an empty table
 * that looks like "nothing matches" and is really "you are past the end".
 */
export default function AppointmentsPage() {
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [page, setPage] = useState(0);
  const employees = useResource<EmployeeList>('/employees');

  function set<K extends keyof Filters>(field: K, value: Filters[K]) {
    setFilters((current) => ({ ...current, [field]: value }));
    setPage(0);
  }

  const filtered =
    filters.from !== '' || filters.to !== '' || filters.status !== '' || filters.employeeId !== '';

  const path = appointmentsPath({
    ...(filters.from ? { from: filters.from } : {}),
    ...(filters.to ? { to: filters.to } : {}),
    ...(filters.status ? { status: filters.status } : {}),
    ...(filters.employeeId ? { employeeId: filters.employeeId } : {}),
    page,
  });

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-ink text-2xl font-semibold tracking-tight">Appointments</h1>
          <p className="text-ink-muted mt-1 text-sm">
            Everything booked, however it was booked — by you, by a customer, or by the
            receptionist.
          </p>
        </div>
        <ButtonLink href="/appointments/new">New appointment</ButtonLink>
      </div>

      <Card>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Input
            label="From"
            type="date"
            value={filters.from}
            onChange={(event) => set('from', event.target.value)}
          />
          <Input
            label="To"
            type="date"
            value={filters.to}
            min={filters.from || undefined}
            onChange={(event) => set('to', event.target.value)}
            hint="Inclusive."
          />
          <Select
            label="Status"
            value={filters.status}
            onChange={(event) => set('status', event.target.value as '' | AppointmentStatus)}
          >
            <option value="">Any status</option>
            {STATUSES.map((status) => (
              <option key={status} value={status}>
                {STATUS_LABELS[status]}
              </option>
            ))}
          </Select>
          {/*
            The employee list is loaded rather than derived from the appointments on screen: a
            person with nothing booked this week still has to be selectable, and deriving the
            options from the result would make exactly the filter that returns nothing impossible
            to choose.
          */}
          <ResourceGate resource={employees}>
            {(list) => (
              <Select
                label="Employee"
                value={filters.employeeId}
                onChange={(event) => set('employeeId', event.target.value)}
              >
                <option value="">Anyone</option>
                {list.employees.map((employee) => (
                  <option key={employee.id} value={employee.id}>
                    {employee.fullName}
                    {employee.active ? '' : ' · inactive'}
                  </option>
                ))}
              </Select>
            )}
          </ResourceGate>
        </div>

        {filtered && (
          <div className="mt-4">
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setFilters(NO_FILTERS);
                setPage(0);
              }}
            >
              Clear filters
            </Button>
          </div>
        )}
      </Card>

      <AppointmentsScreen
        key={path}
        path={path}
        filtered={filtered}
        onClearFilters={() => {
          setFilters(NO_FILTERS);
          setPage(0);
        }}
        onPage={setPage}
      />
    </div>
  );
}
