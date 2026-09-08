'use client';

import Link from 'next/link';
import { StatusBadge } from '@/components/status-badge';
import { Button, ButtonLink, EmptyState, ResourceGate, Table, Td, Th } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { AppointmentPage } from '@/lib/appointments';
import { formatMoney, formatShortDate, formatTimeRange } from '@/lib/time';

/**
 * A page of appointments, keyed on its own question by the caller.
 *
 * `useResource` keeps the data it last loaded when a later load fails, which is right for a screen
 * reloading one resource and wrong here: the previous answer was about a different week, a
 * different person or a different status, and leaving it under changed filters would state
 * something false. A remount makes "this is a new question" the only thing this component can say,
 * and it is what puts the loading and error states back on screen instead of hiding them behind a
 * stale success (the phase-05 frontend handoff §4.2).
 *
 * The pager lives here rather than with the filters because the totals it needs are on the
 * response. A pager outside would either have to guess how many pages there are or keep showing
 * the last answer's count under a question that has moved.
 */
export function AppointmentsScreen({
  path,
  filtered,
  onClearFilters,
  onPage,
}: {
  path: string;
  /** Whether any filter is set, which is what makes an empty result mean two different things. */
  filtered: boolean;
  onClearFilters: () => void;
  onPage: (page: number) => void;
}) {
  const appointments = useResource<AppointmentPage>(path);

  return (
    <ResourceGate resource={appointments}>
      {(result) => {
        if (result.content.length === 0) {
          return filtered ? (
            <EmptyState
              title="Nothing matches those filters"
              description="No appointment falls inside that date range with that status and that person. Widening any one of them is usually enough."
              action={
                <Button variant="secondary" onClick={onClearFilters}>
                  Clear filters
                </Button>
              }
            />
          ) : (
            <EmptyState
              title="No appointments yet"
              description="Nothing has been booked. Take a booking here and it appears on this list, on the customer’s profile, and in the availability every other screen computes."
              action={<ButtonLink href="/appointments/new">Book an appointment</ButtonLink>}
            />
          );
        }

        return (
          <div className="flex flex-col gap-4">
            <Table>
              <thead>
                <tr>
                  <Th>When</Th>
                  <Th>Customer</Th>
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
                    <Td>
                      <Link
                        href={`/customers/${appointment.customer.id}`}
                        className="text-ink hover:underline"
                      >
                        {appointment.customer.fullName}
                      </Link>
                      <p className="text-ink-muted mt-0.5 text-xs">{appointment.customer.phone}</p>
                    </Td>
                    <Td className="text-ink-muted">{appointment.service.name}</Td>
                    <Td className="text-ink-muted">{appointment.employee.name}</Td>
                    {/*
                      The snapshot taken at booking, not the service's price today — which is the
                      whole reason the column exists on the appointment. Rendering the service's
                      current price here would rewrite history every time an owner raised a price.
                    */}
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

            <Pager page={result} onPage={onPage} />
          </div>
        );
      }}
    </ResourceGate>
  );
}

function Pager({ page, onPage }: { page: AppointmentPage; onPage: (page: number) => void }) {
  const first = page.page * page.size + 1;
  const last = first + page.content.length - 1;

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p className="text-ink-muted text-sm tabular-nums">
        {first}–{last} of {page.totalElements}
      </p>
      {page.totalPages > 1 && (
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            size="sm"
            disabled={page.page === 0}
            onClick={() => onPage(page.page - 1)}
          >
            Previous
          </Button>
          <span className="text-ink-muted text-sm tabular-nums">
            Page {page.page + 1} of {page.totalPages}
          </span>
          <Button
            variant="secondary"
            size="sm"
            disabled={page.page + 1 >= page.totalPages}
            onClick={() => onPage(page.page + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  );
}
