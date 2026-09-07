'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ActiveToggle } from '@/components/active-toggle';
import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { ServiceList } from '@/lib/catalog';
import { employeeApi, type EmployeeDetail, type TimeOffList, type WeekSchedule } from '@/lib/staff';
import { EmployeeForm } from '../employee-form';
import { ScheduleSection } from './schedule-section';
import { ServicesSection } from './services-section';
import { TimeOffSection } from './time-off-section';

/**
 * One person, four concerns: who they are, what they provide, when they work, and when they are
 * away.
 *
 * Stacked rather than behind sub-navigation the way Settings is. Settings is five screens an owner
 * visits one at a time, months apart; this is four steps an owner works through in one sitting the
 * first time they add someone — and three of them are the difference between an employee who can
 * be booked and one who cannot. Tabs would hide exactly the steps the onboarding checklist is
 * pushing them towards.
 *
 * Each section loads and saves independently, so a failure in one does not take the others with
 * it, and none of them waits on the rest to appear.
 */
export default function EmployeeDetailPage() {
  const { id } = useParams<{ id: string }>();
  const employee = useResource<EmployeeDetail>(`/employees/${id}`);
  const services = useResource<ServiceList>('/services');
  const schedule = useResource<WeekSchedule>(`/employees/${id}/schedule`);
  const timeOff = useResource<TimeOffList>(`/employees/${id}/time-off`);

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/employees" className="text-ink-muted text-sm hover:underline">
          ← Employees
        </Link>
      </div>

      <ResourceGate resource={employee}>
        {(detail) => (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <h1 className="text-ink text-2xl font-semibold tracking-tight">
                  {detail.fullName}
                </h1>
                <p className="text-ink-muted mt-1 text-sm">
                  {detail.active
                    ? 'Active — customers can book with them.'
                    : 'Inactive — customers cannot book with them.'}
                </p>
              </div>
              <ActiveToggle
                active={detail.active}
                subject="employee"
                name={detail.fullName}
                size="md"
                onToggle={async (active) => {
                  const change = await employeeApi.setActive(detail.id, active);
                  employee.set(change.employee);
                  return change.affectedFutureAppointments;
                }}
              />
            </div>

            {/* Keyed on the save timestamp, so a successful save re-baselines the form against
                what was stored rather than against what the screen first loaded. */}
            <EmployeeForm key={detail.updatedAt} employee={detail} onSaved={employee.set} />

            <ResourceGate resource={services}>
              {(catalogue) => (
                <ServicesSection
                  employee={detail}
                  services={catalogue.services}
                  onSaved={(serviceIds) => employee.set({ ...detail, serviceIds })}
                />
              )}
            </ResourceGate>

            <ResourceGate resource={schedule}>
              {(week) => (
                <ScheduleSection
                  employeeId={detail.id}
                  name={detail.fullName}
                  week={week}
                  onSaved={schedule.set}
                />
              )}
            </ResourceGate>

            <ResourceGate resource={timeOff}>
              {(list) => (
                <TimeOffSection
                  employeeId={detail.id}
                  name={detail.fullName}
                  list={list}
                  onChanged={timeOff.reload}
                />
              )}
            </ResourceGate>
          </>
        )}
      </ResourceGate>
    </div>
  );
}
