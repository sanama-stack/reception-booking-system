'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ActiveToggle } from '@/components/active-toggle';
import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { WeekHours } from '@/lib/business';
import { serviceApi, type ServiceDetail } from '@/lib/catalog';
import type { EmployeeList } from '@/lib/staff';
import { ServiceForm } from '../service-form';
import { DeleteService } from './delete-service';

export default function ServiceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const service = useResource<ServiceDetail>(`/services/${id}`);
  const employees = useResource<EmployeeList>('/employees');
  const hours = useResource<WeekHours>('/business/hours');

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/services" className="text-ink-muted text-sm hover:underline">
          ← Services
        </Link>
      </div>

      <ResourceGate resource={service}>
        {(detail) => (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <h1 className="text-ink text-2xl font-semibold tracking-tight">{detail.name}</h1>
                <p className="text-ink-muted mt-1 text-sm">
                  {detail.active
                    ? 'Active — customers can book this.'
                    : 'Inactive — customers cannot book this.'}
                </p>
              </div>
              <ActiveToggle
                active={detail.active}
                subject="service"
                name={detail.name}
                size="md"
                onToggle={async (active) => {
                  const change = await serviceApi.setActive(detail.id, active);
                  service.set(change.service);
                  return change.affectedFutureAppointments;
                }}
              />
            </div>

            <ResourceGate resource={employees}>
              {(staff) => (
                <ResourceGate resource={hours}>
                  {(week) => (
                    // Keyed on the save timestamp: a successful save returns the stored service,
                    // and remounting re-baselines the form against it. Without that, "what has
                    // changed?" would keep being asked against the values the screen was first
                    // loaded with, and a second save would re-send fields the first one stored —
                    // including the name, which would be re-checked for uniqueness.
                    <ServiceForm
                      key={detail.updatedAt}
                      service={detail}
                      employees={staff.employees}
                      week={week}
                      currency={detail.price.currency}
                      onSaved={service.set}
                    />
                  )}
                </ResourceGate>
              )}
            </ResourceGate>

            <DeleteService service={detail} />
          </>
        )}
      </ResourceGate>
    </div>
  );
}
