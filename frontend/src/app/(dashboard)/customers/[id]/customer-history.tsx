'use client';

import Link from 'next/link';
import { StatusBadge } from '@/components/status-badge';
import { Button, EmptyState, ResourceGate, Table, Td, Th } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { AppointmentPage } from '@/lib/appointments';
import { formatMoney, formatShortDate, formatTimeRange } from '@/lib/time';

/**
 * One customer's bookings, newest first.
 *
 * The opposite order to `/appointments`, deliberately: that list is a day being worked through and
 * reads forwards, while a profile is read as a history and the most recent visit is the one being
 * asked about. The server owns both orders, so neither screen sorts anything.
 *
 * Keyed on its page by the caller, for the same reason every other paged read here is.
 */
export function CustomerHistory({
  path,
  onPage,
}: {
  path: string;
  onPage: (page: number) => void;
}) {
  const history = useResource<AppointmentPage>(path);

  return (
    <ResourceGate resource={history}>
      {(result) => {
        if (result.content.length === 0) {
          return (
            <EmptyState
              title="Nothing booked"
              description="This customer exists because something was booked for them, so an empty history means every one of those has since been deleted."
            />
          );
        }

        return (
          <div className="flex flex-col gap-4">
            <Table>
              <thead>
                <tr>
                  <Th>When</Th>
                  <Th>Service</Th>
                  <Th>With</Th>
                  <Th>Price</Th>
                  <Th>Status</Th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((appointment) => (
                  <tr key={appointment.id}>
                    <Td className="whitespace-nowrap">
                      <Link
                        href={`/appointments/${appointment.id}`}
                        className="text-ink font-medium hover:underline"
                      >
                        {formatShortDate(appointment.startsAt, result.timezone)}
                      </Link>
                      <p className="text-ink-muted mt-0.5 text-xs tabular-nums">
                        {formatTimeRange(appointment.startsAt, appointment.endsAt, result.timezone)}
                      </p>
                    </Td>
                    <Td className="text-ink-muted">{appointment.service.name}</Td>
                    <Td className="text-ink-muted">{appointment.employee.name}</Td>
                    <Td className="whitespace-nowrap tabular-nums">
                      {formatMoney(appointment.price.amount, appointment.price.currency)}
                    </Td>
                    <Td>
                      <StatusBadge status={appointment.status} />
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>

            {result.totalPages > 1 && (
              <div className="flex items-center justify-end gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={result.page === 0}
                  onClick={() => onPage(result.page - 1)}
                >
                  Previous
                </Button>
                <span className="text-ink-muted text-sm tabular-nums">
                  Page {result.page + 1} of {result.totalPages}
                </span>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={result.page + 1 >= result.totalPages}
                  onClick={() => onPage(result.page + 1)}
                >
                  Next
                </Button>
              </div>
            )}
          </div>
        );
      }}
    </ResourceGate>
  );
}
