'use client';

import Link from 'next/link';
import { ActiveToggle } from '@/components/active-toggle';
import { ButtonLink, EmptyState, Table, Td, Th, cn } from '@/components/ui';
import { employeeApi, type EmployeeDetail, type EmployeeList } from '@/lib/staff';

export function EmployeesScreen({
  list,
  onChanged,
}: {
  list: EmployeeList;
  onChanged: () => Promise<void>;
}) {
  if (list.employees.length === 0) {
    return (
      <EmptyState
        title="Nobody here yet"
        description="Appointments are booked with a person. Add the people who provide your services — including yourself, if you provide them."
        action={<ButtonLink href="/employees/new">Add your first employee</ButtonLink>}
      />
    );
  }

  return (
    <Table>
      <thead>
        <tr>
          <Th>Name</Th>
          <Th>Job title</Th>
          <Th>Provides</Th>
          <Th>
            <span className="sr-only">Actions</span>
          </Th>
        </tr>
      </thead>
      <tbody>
        {list.employees.map((employee) => (
          <tr key={employee.id}>
            <Td>
              <Link
                href={`/employees/${employee.id}`}
                className={cn(
                  'font-medium hover:underline',
                  employee.active ? 'text-ink' : 'text-ink-muted',
                )}
              >
                {employee.fullName}
              </Link>
              {!employee.active && (
                <span className="text-ink-muted bg-surface-muted ml-2 rounded px-1.5 py-0.5 text-xs">
                  Inactive
                </span>
              )}
              {employee.email && <p className="text-ink-muted mt-0.5 text-xs">{employee.email}</p>}
            </Td>
            <Td className="text-ink-muted">{employee.jobTitle ?? '—'}</Td>
            <Td>
              {employee.serviceIds.length === 0 ? (
                <span className="text-danger">No services</span>
              ) : (
                <span className="text-ink-muted">
                  {employee.serviceIds.length}{' '}
                  {employee.serviceIds.length === 1 ? 'service' : 'services'}
                </span>
              )}
            </Td>
            <Td className="text-right">
              <div className="flex justify-end gap-2">
                <ActiveToggle
                  active={employee.active}
                  subject="employee"
                  name={employee.fullName}
                  onToggle={(active) => toggle(employee, active, onChanged)}
                />
              </div>
            </Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

async function toggle(
  employee: EmployeeDetail,
  active: boolean,
  onChanged: () => Promise<void>,
): Promise<number> {
  const change = await employeeApi.setActive(employee.id, active);
  await onChanged();
  return change.affectedFutureAppointments;
}
