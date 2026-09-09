'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { StatusBadge } from '@/components/status-badge';
import { Button, Card, CardHeader, EmptyState, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import {
  appointmentPath,
  type AppointmentDetail,
  type AppointmentSource,
  type AppointmentWithHistory,
} from '@/lib/appointments';
import { useSession } from '@/lib/auth';
import type { ServiceList } from '@/lib/catalog';
import type { EmployeeList } from '@/lib/staff';
import { formatDate, formatMoney, formatTimeRange, type Timezone } from '@/lib/time';
import { AppointmentActions } from './appointment-actions';
import { HistoryList } from './history-list';
import { RescheduleSection } from './reschedule-section';

const SOURCES: Record<AppointmentSource, string> = {
  DASHBOARD: 'Taken on this dashboard',
  CLASSIC: 'Booked on your public page',
  AI: 'Booked with the receptionist',
};

/**
 * One appointment, everything recorded about it, and everything that can still be done to it.
 *
 * The audit trail is the half of this screen that cannot be reconstructed from anywhere else. The
 * fields above it describe the appointment *now*; a reschedule overwrites the very times somebody
 * would be asking about, so the history is the only place the answer to "what was it before?"
 * survives.
 *
 * The services and employees lists are loaded here rather than inside the reschedule section, so
 * opening it is instant and a failure to load them shows up as this screen's error rather than as
 * an empty picker inside a panel that has just opened.
 */
export default function AppointmentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { session } = useSession();
  const appointment = useResource<AppointmentWithHistory>(appointmentPath(id));
  const services = useResource<ServiceList>('/services');
  const employees = useResource<EmployeeList>('/employees');
  const [moving, setMoving] = useState(false);

  if (!session) return null;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <Link href="/appointments" className="text-ink-muted text-sm hover:underline">
          ← Appointments
        </Link>
      </div>

      <ResourceGate resource={appointment}>
        {(detail) => (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-3">
                  <h1 className="text-ink text-2xl font-semibold tracking-tight">
                    {formatDate(detail.appointment.startsAt, detail.timezone)}
                  </h1>
                  <StatusBadge status={detail.appointment.status} />
                </div>
                <p className="text-ink-muted mt-1 text-sm tabular-nums">
                  {formatTimeRange(
                    detail.appointment.startsAt,
                    detail.appointment.endsAt,
                    detail.timezone,
                  )}{' '}
                  · {detail.timezone}
                </p>
              </div>
              <div className="text-right">
                <p className="text-ink-muted text-xs tracking-wide uppercase">Confirmation code</p>
                <p className="text-ink font-mono text-lg font-semibold">
                  {detail.appointment.confirmationCode}
                </p>
              </div>
            </div>

            {detail.appointment.status === 'CONFIRMED' && (
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="secondary" size="sm" onClick={() => setMoving((open) => !open)}>
                  {moving ? 'Stop moving' : 'Reschedule'}
                </Button>
                <AppointmentActions
                  appointment={detail.appointment}
                  onUpdated={appointment.set}
                  onReload={appointment.reload}
                />
              </div>
            )}

            {moving && detail.appointment.status === 'CONFIRMED' && (
              <ResourceGate resource={services}>
                {(catalogue) => (
                  <ResourceGate resource={employees}>
                    {(staff) => (
                      <RescheduleSection
                        appointment={detail.appointment}
                        service={
                          catalogue.services.find(
                            (candidate) => candidate.id === detail.appointment.service.id,
                          ) ?? null
                        }
                        employees={staff}
                        timezone={detail.timezone}
                        onUpdated={appointment.set}
                        onReload={appointment.reload}
                        onDone={() => setMoving(false)}
                      />
                    )}
                  </ResourceGate>
                )}
              </ResourceGate>
            )}

            <Facts appointment={detail.appointment} timezone={detail.timezone} />

            <Card>
              <CardHeader
                title="History"
                description="Every state change, in the order it happened. Written in the same transaction as the change itself, so nothing here describes something that did not take."
              />
              {detail.history.length === 0 ? (
                <EmptyState
                  title="No history"
                  description="Every appointment records at least its own creation, so an empty trail means something was written outside the normal path."
                />
              ) : (
                <HistoryList history={detail.history} timezone={detail.timezone} />
              )}
            </Card>
          </>
        )}
      </ResourceGate>
    </div>
  );
}

function Facts({ appointment, timezone }: { appointment: AppointmentDetail; timezone: Timezone }) {
  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
      <Card>
        <CardHeader title="Customer" />
        <dl className="flex flex-col gap-3 text-sm">
          <Fact label="Name">
            <Link
              href={`/customers/${appointment.customer.id}`}
              className="text-ink hover:underline"
            >
              {appointment.customer.fullName}
            </Link>
          </Fact>
          <Fact label="Phone">{appointment.customer.phone}</Fact>
          <Fact label="Email">{appointment.customer.email ?? '—'}</Fact>
          <Fact label="Note">{appointment.customerNote ?? '—'}</Fact>
        </dl>
      </Card>

      <Card>
        <CardHeader title="Booking" />
        <dl className="flex flex-col gap-3 text-sm">
          <Fact label="Service">{appointment.service.name}</Fact>
          <Fact label="With">{appointment.employee.name}</Fact>
          {/*
            The price agreed at booking, not the service's price today. The snapshot is the whole
            reason the column exists: changing a price must not rewrite what a past customer was
            charged, and showing the current one here would undo that on the screen.
          */}
          <Fact label="Price">
            {formatMoney(appointment.price.amount, appointment.price.currency)}
            <span className="text-ink-muted ml-2 text-xs">as booked</span>
          </Fact>
          <Fact label="Source">{SOURCES[appointment.source]}</Fact>
          {appointment.status === 'CANCELLED' && (
            <>
              <Fact label="Cancelled">
                {appointment.cancelledAt ? formatDate(appointment.cancelledAt, timezone) : '—'}
                {appointment.cancelledBy && (
                  <span className="text-ink-muted ml-2 text-xs">
                    by the {appointment.cancelledBy === 'CUSTOMER' ? 'customer' : 'business'}
                  </span>
                )}
              </Fact>
              <Fact label="Reason">{appointment.cancellationReason ?? '—'}</Fact>
            </>
          )}
        </dl>
      </Card>
    </div>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-ink-muted text-xs tracking-wide uppercase">{label}</dt>
      <dd className="text-ink">{children}</dd>
    </div>
  );
}
