'use client';

import { useState } from 'react';
import { AssignmentPicker } from '@/components/assignment-picker';
import { Button, ButtonLink, Card, CardHeader, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { formatDuration, type ServiceDetail } from '@/lib/catalog';
import { employeeApi, type EmployeeDetail } from '@/lib/staff';
import { formatMoney } from '@/lib/time';

function sameSet(a: string[], b: string[]): boolean {
  return a.length === b.length && [...a].sort().join() === [...b].sort().join();
}

/**
 * What this person can be booked for — the other view of `employee_services`.
 *
 * The same control as the service form's "who provides it", because it is the same table read from
 * the other end. It is a replace, not an add-and-remove: the boxes say what is true now.
 */
export function ServicesSection({
  employee,
  services,
  onSaved,
}: {
  employee: EmployeeDetail;
  services: ServiceDetail[];
  onSaved: (serviceIds: string[]) => void;
}) {
  const toast = useToast();
  const [selected, setSelected] = useState<string[]>(employee.serviceIds);
  const [saving, setSaving] = useState(false);

  const changed = !sameSet(selected, employee.serviceIds);

  async function save() {
    setSaving(true);
    try {
      const assigned = await employeeApi.replaceServices(employee.id, selected);
      // The set the server answers with, not the one that was submitted.
      onSaved(assigned.serviceIds);
      setSelected(assigned.serviceIds);
      toast('What they provide has been saved.', 'success');
    } catch (cause) {
      toast(
        cause instanceof ApiError ? cause.message : 'The assignments could not be saved.',
        'error',
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <Card>
      <CardHeader
        title="What they provide"
        description="Customers can only book this person for the services ticked here."
      />
      <AssignmentPicker
        legend="Services"
        options={services.map((service) => ({
          id: service.id,
          label: service.name,
          hint: `${formatDuration(service.durationMinutes)} · ${formatMoney(
            service.price.amount,
            service.price.currency,
          )}`,
          inactive: !service.active,
        }))}
        selected={selected}
        onChange={setSelected}
        emptyTitle="No services to assign yet"
        emptyDescription="Add what your business sells, then come back and say who provides each one."
        emptyAction={
          <ButtonLink href="/services/new" variant="secondary" size="sm">
            Add a service
          </ButtonLink>
        }
        disabled={saving}
      />
      {services.length > 0 && (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button onClick={() => void save()} loading={saving} disabled={!changed}>
            Save what they provide
          </Button>
          {!changed && <p className="text-ink-muted text-sm">No changes to save.</p>}
        </div>
      )}
    </Card>
  );
}
