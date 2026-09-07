'use client';

import { ButtonLink, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { EmployeeList } from '@/lib/staff';
import { EmployeesScreen } from './employees-screen';

/**
 * Who appointments are booked with.
 *
 * `GET /employees` carries `serviceIds` on every row, so "assigned to nothing" — the mirror of the
 * services list's "nobody assigned" — needs no request per person.
 */
export default function EmployeesPage() {
  const employees = useResource<EmployeeList>('/employees');

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-ink text-2xl font-semibold tracking-tight">Employees</h1>
          <p className="text-ink-muted mt-1 text-sm">
            The people customers book with, what each of them provides, and when they work.
          </p>
        </div>
        <ButtonLink href="/employees/new">New employee</ButtonLink>
      </div>

      <ResourceGate resource={employees}>
        {(list) => <EmployeesScreen list={list} onChanged={employees.reload} />}
      </ResourceGate>
    </div>
  );
}
