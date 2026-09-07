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
import { employeeApi, type TimeOff, type TimeOffList } from '@/lib/staff';
import { formatIsoDate } from '@/lib/time';

/**
 * Time off, which is the closures form applied to a person.
 *
 * Inclusive local dates go in; both representations plus the zone come back, so nothing is
 * re-derived here — `endsAt` is the start of the day *after* the last day off, and formatting it
 * would tell the owner they are away a day longer than they said.
 */
export function TimeOffSection({
  employeeId,
  name,
  list,
  onChanged,
}: {
  employeeId: string;
  name: string;
  list: TimeOffList;
  onChanged: () => Promise<void>;
}) {
  const toast = useToast();
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [reason, setReason] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [deleting, setDeleting] = useState<TimeOff | null>(null);
  const [deletingBusy, setDeletingBusy] = useState(false);

  async function onCreate(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCreating(true);
    setError(null);
    try {
      await employeeApi.createTimeOff(employeeId, {
        startDate,
        endDate,
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      });
      await onChanged();
      setStartDate('');
      setEndDate('');
      setReason('');
      toast('Time off added.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The time off could not be added.', 'error');
    } finally {
      setCreating(false);
    }
  }

  async function onDelete(entry: TimeOff) {
    setDeletingBusy(true);
    try {
      await employeeApi.deleteTimeOff(employeeId, entry.id);
      await onChanged();
      toast('Time off removed.', 'success');
      setDeleting(null);
    } catch (cause) {
      toast(
        cause instanceof ApiError ? cause.message : 'The time off could not be removed.',
        'error',
      );
    } finally {
      setDeletingBusy(false);
    }
  }

  const unfielded = error && Object.keys(error.fieldErrors).length === 0 ? error.message : null;

  return (
    <Card>
      <CardHeader
        title="Time off"
        description={`Days ${name} is away, on top of their working schedule. Dates are in ${list.timezone}.`}
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
            label="First day off"
            type="date"
            value={startDate}
            required
            onChange={(event) => {
              const value = event.target.value;
              setStartDate(value);
              // A single day off is the common case, and an end before the start is never what was
              // meant — so the end follows the start until the owner moves it themselves.
              if (!endDate || endDate < value) setEndDate(value);
            }}
            error={error?.fieldErrors.startDate}
          />
          <Input
            label="Last day off"
            type="date"
            value={endDate}
            required
            min={startDate || undefined}
            onChange={(event) => setEndDate(event.target.value)}
            hint="Inclusive — they are back the next day."
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
            Add time off
          </Button>
        </div>
      </form>

      <div className="mt-6">
        {list.timeOff.length === 0 ? (
          <EmptyState
            title="No time off booked"
            description="Their working schedule applies every week. Add time off for the days it does not."
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
              {list.timeOff.map((entry) => (
                <tr key={entry.id}>
                  <Td>{describe(entry)}</Td>
                  <Td className="text-ink-muted">{entry.reason ?? '—'}</Td>
                  <Td className="text-right">
                    <Button variant="ghost" size="sm" onClick={() => setDeleting(entry)}>
                      Remove
                    </Button>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </div>

      <ConfirmDialog
        open={deleting !== null}
        title="Remove this time off?"
        confirmLabel="Remove time off"
        tone="danger"
        busy={deletingBusy}
        onConfirm={() => deleting && void onDelete(deleting)}
        onCancel={() => !deletingBusy && setDeleting(null)}
      >
        <p>{deleting ? describe(deleting) : ''}</p>
        <p>Those days go back to their normal working schedule and can be booked again.</p>
      </ConfirmDialog>
    </Card>
  );
}

/** `24 December 2026` for one day, `24 December 2026 – 26 December 2026` for a span. */
function describe(entry: TimeOff): string {
  return entry.startDate === entry.endDate
    ? formatIsoDate(entry.startDate)
    : `${formatIsoDate(entry.startDate)} – ${formatIsoDate(entry.endDate)}`;
}
