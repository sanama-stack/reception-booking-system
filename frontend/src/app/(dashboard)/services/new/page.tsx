'use client';

import Link from 'next/link';
import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import type { WeekHours } from '@/lib/business';
import type { EmployeeList } from '@/lib/staff';
import { ServiceForm } from '../service-form';

/**
 * Both requests start together on mount, so the two gates below are one wait rather than two: the
 * employee list is needed to render the assignment control at all, and the opening hours are what
 * the long-duration warning is measured against.
 */
export default function NewServicePage() {
  const { session } = useSession();
  const employees = useResource<EmployeeList>('/employees');
  const hours = useResource<WeekHours>('/business/hours');

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/services" className="text-ink-muted text-sm hover:underline">
          ← Services
        </Link>
        <h1 className="text-ink mt-2 text-2xl font-semibold tracking-tight">New service</h1>
        <p className="text-ink-muted mt-1 text-sm">
          Something a customer can book. You can change any of this later.
        </p>
      </div>

      <ResourceGate resource={employees}>
        {(staff) => (
          <ResourceGate resource={hours}>
            {(week) => (
              <ServiceForm
                service={null}
                employees={staff.employees}
                week={week}
                currency={session?.business.currency ?? ''}
                onSaved={() => undefined}
              />
            )}
          </ResourceGate>
        )}
      </ResourceGate>
    </div>
  );
}
