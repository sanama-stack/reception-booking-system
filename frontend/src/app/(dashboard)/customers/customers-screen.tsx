'use client';

import Link from 'next/link';
import { Button, EmptyState, ResourceGate, Table, Td, Th } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { CustomerPage } from '@/lib/customers';
import { formatDate, type Timezone } from '@/lib/time';

/**
 * A page of customers, keyed on its own question by the caller — same rule as the appointment
 * list, and the same failure it prevents: a previous search's results sitting under a new term.
 *
 * **The timezone is passed in rather than read off the response.** Unlike the appointment
 * envelopes, `/customers` carries no zone, because nothing it returns is a business-local time: a
 * customer's `createdAt` and their last appointment are plain instants. They still have to be
 * *rendered* in the business's zone like everything else, so it is handed down explicitly rather
 * than falling back to the browser's (ADR-0003).
 */
export function CustomersScreen({
  path,
  searching,
  timezone,
  onClearSearch,
  onPage,
}: {
  path: string;
  searching: boolean;
  timezone: Timezone;
  onClearSearch: () => void;
  onPage: (page: number) => void;
}) {
  const customers = useResource<CustomerPage>(path);

  return (
    <ResourceGate resource={customers}>
      {(result) => {
        if (result.content.length === 0) {
          return searching ? (
            <EmptyState
              title="Nobody matches that"
              description="The search looks at names, phone numbers and email addresses. A partial phone number works, as long as it is written the way it was stored."
              action={
                <Button variant="secondary" onClick={onClearSearch}>
                  Clear search
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No customers yet"
              description="A customer is created by their first booking — there is nothing to add here by hand. Take a booking and they appear on this list."
            />
          );
        }

        return (
          <div className="flex flex-col gap-4">
            <Table>
              <thead>
                <tr>
                  <Th>Name</Th>
                  <Th>Phone</Th>
                  <Th>Email</Th>
                  <Th>Appointments</Th>
                  <Th>Last booking</Th>
                </tr>
              </thead>
              <tbody>
                {result.content.map((customer) => (
                  <tr key={customer.id}>
                    <Td>
                      <Link
                        href={`/customers/${customer.id}`}
                        className="text-ink font-medium hover:underline"
                      >
                        {customer.fullName}
                      </Link>
                    </Td>
                    <Td className="text-ink-muted whitespace-nowrap tabular-nums">
                      {customer.phone}
                    </Td>
                    <Td className="text-ink-muted">{customer.email ?? '—'}</Td>
                    <Td className="tabular-nums">{customer.totalAppointments}</Td>
                    <Td className="text-ink-muted whitespace-nowrap">
                      {customer.lastAppointmentAt
                        ? formatDate(customer.lastAppointmentAt, timezone)
                        : '—'}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>

            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-ink-muted text-sm tabular-nums">
                {result.page * result.size + 1}–{result.page * result.size + result.content.length}{' '}
                of {result.totalElements}
              </p>
              {result.totalPages > 1 && (
                <div className="flex items-center gap-2">
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
          </div>
        );
      }}
    </ResourceGate>
  );
}
