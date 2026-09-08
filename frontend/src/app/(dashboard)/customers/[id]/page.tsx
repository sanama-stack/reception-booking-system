'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { ButtonLink, Card, CardHeader, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import { customerHistoryPath, customerPath, type SingleCustomer } from '@/lib/customers';
import { formatDate, type Timezone } from '@/lib/time';
import { CustomerForm } from './customer-form';
import { CustomerHistory } from './customer-history';

/**
 * One customer: who they are, and everything they have ever booked.
 *
 * The profile and the history are separate reads because they change for different reasons and at
 * different rates — correcting a spelling should not re-fetch a hundred appointments, and paging
 * through the history should not re-fetch the name. Each loads, fails and reloads on its own.
 */
export default function CustomerDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { session } = useSession();
  const customer = useResource<SingleCustomer>(customerPath(id));
  const [page, setPage] = useState(0);

  if (!session) return null;

  const historyPath = customerHistoryPath(id, page);

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/customers" className="text-ink-muted text-sm hover:underline">
          ← Customers
        </Link>
      </div>

      <ResourceGate resource={customer}>
        {({ customer: detail }) => (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <h1 className="text-ink text-2xl font-semibold tracking-tight">
                  {detail.fullName}
                </h1>
                <p className="text-ink-muted mt-1 text-sm">
                  Customer since {formatDate(detail.createdAt, session.business.timezone)}.
                </p>
              </div>
              <ButtonLink href="/appointments/new">Book for them</ButtonLink>
            </div>

            <Stats detail={detail} timezone={session.business.timezone} />

            <CustomerForm
              customer={detail}
              onSaved={(updated) => customer.set({ customer: updated })}
            />

            <Card>
              <CardHeader
                title="Appointment history"
                description="Newest first, in every status — including the ones that were cancelled."
              />
              <CustomerHistory key={historyPath} path={historyPath} onPage={setPage} />
            </Card>
          </>
        )}
      </ResourceGate>
    </div>
  );
}

function Stats({
  detail,
  timezone,
}: {
  detail: { totalAppointments: number; lastAppointmentAt: string | null };
  timezone: Timezone;
}) {
  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <Card>
        <p className="text-ink-muted text-xs tracking-wide uppercase">Appointments</p>
        <p className="text-ink mt-1 text-2xl font-semibold tabular-nums">
          {detail.totalAppointments}
        </p>
        <p className="text-ink-muted mt-1 text-xs">
          Every booking in any status, not only the ones that went ahead.
        </p>
      </Card>
      <Card>
        <p className="text-ink-muted text-xs tracking-wide uppercase">Most recent</p>
        <p className="text-ink mt-1 text-2xl font-semibold">
          {detail.lastAppointmentAt ? formatDate(detail.lastAppointmentAt, timezone) : '—'}
        </p>
        {/*
          "Most recent" rather than "last visit": the server answers `max(startsAt)` over every
          booking in any status, so for a customer with something coming up this is a future date.
          A label saying "last visit" would be wrong for exactly the customers who matter most.
        */}
        <p className="text-ink-muted mt-1 text-xs">
          The latest booking on record — a date still to come, if they have one.
        </p>
      </Card>
    </div>
  );
}
