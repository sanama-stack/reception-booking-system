'use client';

import { useState } from 'react';
import {
  Button,
  Card,
  CardHeader,
  ConfirmDialog,
  EmptyState,
  Input,
  Table,
  Td,
  Th,
  useToast,
} from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { businessApi, type Closure, type ClosureList } from '@/lib/business';
import { formatIsoDate } from '@/lib/time';

export function ClosuresScreen({
  list,
  onChanged,
}: {
  list: ClosureList;
  onChanged: () => Promise<void>;
}) {
  const toast = useToast();
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [reason, setReason] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [deleting, setDeleting] = useState<Closure | null>(null);
  const [deletingBusy, setDeletingBusy] = useState(false);

  async function onCreate(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreating(true);
    setError(null);
    try {
      const created = await businessApi.createClosure({
        startDate,
        endDate,
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      });
      await onChanged();
      setStartDate('');
      setEndDate('');
      setReason('');
      // Nothing is cancelled by adding a closure — the owner is told what it covers and decides
      // (docs/01-prd.md FR-2). Saying "0 appointments" every time would be noise, so the count
      // speaks only when there is something to say.
      toast(
        created.affectedAppointments > 0
          ? `Closure added. It covers ${created.affectedAppointments} booked ${
              created.affectedAppointments === 1 ? 'appointment' : 'appointments'
            }, which have not been cancelled.`
          : 'Closure added.',
        created.affectedAppointments > 0 ? 'info' : 'success',
      );
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The closure could not be added.', 'error');
    } finally {
      setCreating(false);
    }
  }

  async function onDelete(closure: Closure) {
    setDeletingBusy(true);
    try {
      await businessApi.deleteClosure(closure.id);
      await onChanged();
      toast('Closure removed.', 'success');
      setDeleting(null);
    } catch (cause) {
      toast(
        cause instanceof ApiError ? cause.message : 'The closure could not be removed.',
        'error',
      );
    } finally {
      setDeletingBusy(false);
    }
  }

  const unfielded = error && Object.keys(error.fieldErrors).length === 0 ? error.message : null;

  return (
    <>
      <Card>
        <CardHeader
          title="Add a closure"
          description="A holiday, a refurbishment, a day off. Nobody can book on these days."
        />
        <form onSubmit={onCreate} className="flex flex-col gap-4" noValidate>
          {unfielded && (
            <p
              role="alert"
              className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
            >
              {unfielded}
            </p>
          )}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="First day closed"
              type="date"
              value={startDate}
              required
              onChange={(event) => {
                const value = event.target.value;
                setStartDate(value);
                // One-day closures are the common case, and an end before the start is never what
                // was meant — so the end follows the start until the owner moves it themselves.
                if (!endDate || endDate < value) setEndDate(value);
              }}
              error={error?.fieldErrors.startDate}
            />
            <Input
              label="Last day closed"
              type="date"
              value={endDate}
              required
              min={startDate || undefined}
              onChange={(event) => setEndDate(event.target.value)}
              hint="Inclusive — the business reopens the next day."
              error={error?.fieldErrors.endDate}
            />
          </div>
          <Input
            label="Reason"
            value={reason}
            maxLength={200}
            onChange={(event) => setReason(event.target.value)}
            hint="Optional. Shown to you, not to customers."
            error={error?.fieldErrors.reason}
          />
          <div>
            <Button type="submit" loading={creating} disabled={!startDate || !endDate}>
              Add closure
            </Button>
          </div>
        </form>
      </Card>

      <Card>
        <CardHeader
          title="Closures"
          description={`Dates are in ${list.timezone}, your business's timezone.`}
        />
        {list.closures.length === 0 ? (
          <EmptyState
            title="No closures"
            description="Your opening hours apply every week. Add a closure for the days they do not."
          />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Dates</Th>
                <Th>Reason</Th>
                <Th>
                  <span className="sr-only">Actions</span>
                </Th>
              </tr>
            </thead>
            <tbody>
              {list.closures.map((closure) => (
                <tr key={closure.id}>
                  <Td>{describe(closure)}</Td>
                  <Td className="text-ink-muted">{closure.reason ?? '—'}</Td>
                  <Td className="text-right">
                    <Button variant="ghost" size="sm" onClick={() => setDeleting(closure)}>
                      Remove
                    </Button>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <ConfirmDialog
        open={deleting !== null}
        title="Remove this closure?"
        confirmLabel="Remove closure"
        tone="danger"
        busy={deletingBusy}
        onConfirm={() => deleting && void onDelete(deleting)}
        onCancel={() => !deletingBusy && setDeleting(null)}
      >
        <p>{deleting ? describe(deleting) : ''}</p>
        <p>Those days go back to your normal opening hours and can be booked again.</p>
      </ConfirmDialog>
    </>
  );
}

/**
 * `24 December 2026` for one day, `24 December 2026 – 26 December 2026` for a span.
 *
 * Rendered from `startDate`/`endDate`, never from the instants. `endsAt` is the start of the day
 * *after* the last closed day — half-open, which is what the availability engine needs — so
 * formatting it would tell the owner they are closed a day longer than they said.
 */
function describe(closure: Closure): string {
  return closure.startDate === closure.endDate
    ? formatIsoDate(closure.startDate)
    : `${formatIsoDate(closure.startDate)} – ${formatIsoDate(closure.endDate)}`;
}
