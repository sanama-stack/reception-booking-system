'use client';

import Link from 'next/link';
import { ActiveToggle } from '@/components/active-toggle';
import { ButtonLink, EmptyState, Table, Td, Th, cn } from '@/components/ui';
import { formatDuration, serviceApi, type ServiceDetail, type ServiceList } from '@/lib/catalog';
import { formatMoney } from '@/lib/time';

export function ServicesScreen({
  list,
  onChanged,
}: {
  list: ServiceList;
  onChanged: () => Promise<void>;
}) {
  if (list.services.length === 0) {
    return (
      <EmptyState
        title="No services yet"
        description="A service is what a customer books — a haircut, a consultation, a fitting. Nothing can be booked until there is at least one."
        action={<ButtonLink href="/services/new">Add your first service</ButtonLink>}
      />
    );
  }

  return (
    <Table>
      <thead>
        <tr>
          <Th>Service</Th>
          <Th>Length</Th>
          <Th>Price</Th>
          <Th>Who provides it</Th>
          <Th>
            <span className="sr-only">Actions</span>
          </Th>
        </tr>
      </thead>
      <tbody>
        {list.services.map((service) => (
          <tr key={service.id}>
            <Td>
              <Link
                href={`/services/${service.id}`}
                className={cn(
                  'font-medium hover:underline',
                  service.active ? 'text-ink' : 'text-ink-muted',
                )}
              >
                {service.name}
              </Link>
              {!service.active && (
                <span className="text-ink-muted bg-surface-muted ml-2 rounded px-1.5 py-0.5 text-xs">
                  Inactive
                </span>
              )}
              {service.description && (
                <p className="text-ink-muted mt-0.5 line-clamp-1 text-xs">{service.description}</p>
              )}
            </Td>
            <Td className="whitespace-nowrap">{formatDuration(service.durationMinutes)}</Td>
            {/*
              The currency is rendered per row rather than once for the list. A service is stamped
              with the business's currency when it is created and never re-stamped, so a business
              that has switched currency has rows in both — and labelling them all with the current
              one would misprice exactly the rows that need attention.
            */}
            <Td className="whitespace-nowrap">
              {formatMoney(service.price.amount, service.price.currency)}
            </Td>
            <Td>
              {service.employeeIds.length === 0 ? (
                <span className="text-danger">Nobody assigned</span>
              ) : (
                <span className="text-ink-muted">
                  {service.employeeIds.length}{' '}
                  {service.employeeIds.length === 1 ? 'person' : 'people'}
                </span>
              )}
            </Td>
            <Td className="text-right">
              <div className="flex justify-end gap-2">
                <ActiveToggle
                  active={service.active}
                  subject="service"
                  name={service.name}
                  onToggle={(active) => toggle(service, active, onChanged)}
                />
              </div>
            </Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

/**
 * The list holds no state of its own, so a toggle is a write followed by a reload.
 *
 * Reloading rather than patching the row in place is what keeps the list honest about everything a
 * deactivation touches — including, once appointments exist, counts that were computed server-side.
 */
async function toggle(
  service: ServiceDetail,
  active: boolean,
  onChanged: () => Promise<void>,
): Promise<number> {
  const change = await serviceApi.setActive(service.id, active);
  await onChanged();
  return change.affectedFutureAppointments;
}
