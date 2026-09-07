'use client';

import { ButtonLink, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { ServiceList } from '@/lib/catalog';
import { ServicesScreen } from './services-screen';

/**
 * What the business sells.
 *
 * One request, not one per row: `GET /services` carries `employeeIds` on every service, so the
 * most common reason a fully configured service still cannot be booked — nobody is assigned to it
 * — is visible in the list without asking about each one.
 */
export default function ServicesPage() {
  const services = useResource<ServiceList>('/services');

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-ink text-2xl font-semibold tracking-tight">Services</h1>
          <p className="text-ink-muted mt-1 text-sm">
            What customers book — how long each one takes, and what it costs.
          </p>
        </div>
        <ButtonLink href="/services/new">New service</ButtonLink>
      </div>

      <ResourceGate resource={services}>
        {(list) => <ServicesScreen list={list} onChanged={services.reload} />}
      </ResourceGate>
    </div>
  );
}
