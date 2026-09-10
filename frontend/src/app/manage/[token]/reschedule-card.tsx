'use client';

import { useState } from 'react';
import { FORTNIGHT_DAYS, FortnightPicker } from '@/components/fortnight-picker';
import { Button, Card, CardHeader } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { manageAvailabilityPath, publicApi, type ManagedAppointment } from '@/lib/public';
import { isStaleSlot, type AvailableSlot } from '@/lib/scheduling';
import {
  addIsoDays,
  formatIsoDate,
  formatTime,
  toBusinessDate,
  type IsoDate,
  type Timezone,
} from '@/lib/time';

interface Selection {
  startsAt: string;
  employeeId: string;
  employeeName: string;
  /** The zone the chosen time was rendered in, so the summary cannot drift from the grid. */
  timezone: Timezone;
}

/**
 * Moving an appointment: same record, same Confirmation Code, different time.
 *
 * A reschedule is an **update**, not a cancel-and-rebook. Cancelling first would put the original
 * slot back on sale for as long as it took to write the new one, and a concurrent booking taking
 * it would leave the Customer with nothing at all.
 *
 * **The grid comes from `…/manage/availability`, never from the public one.** That endpoint
 * excludes the appointment being moved, which is not a nicety: this appointment blocks the hour it
 * occupies *and* the Buffers either side, so the ordinary grid would refuse to offer a slot fifteen
 * minutes later — the Customer would be told their own booking was in the way. The public grid
 * deliberately has no exclusion parameter at all, because diffing its two answers would tell an
 * anonymous caller that a particular appointment exists.
 *
 * **There is no "with whom" picker, and there cannot be one.** These paths carry no slug, so there
 * is no public Employee list to populate one from, and `ManagedAppointment.employee` is a name
 * without an id, so the current person cannot even be pinned. Every grid is therefore an "anyone
 * eligible" grid — which turns out to be the better screen: it shows the Customer every time that
 * exists rather than only their own person's, and the picker labels each Slot with whoever would
 * perform it whenever that varies. Nobody is moved to a different person without being shown who.
 */
export function RescheduleCard({
  token,
  appointment,
  today,
  onMoved,
  onFailed,
  onDone,
}: {
  token: string;
  appointment: ManagedAppointment;
  today: IsoDate;
  onMoved: (next: ManagedAppointment) => void | Promise<void>;
  onFailed: (cause: ApiError) => void | Promise<void>;
  onDone: () => void;
}) {
  /**
   * Opens on the fortnight containing the appointment as it stands, not on today.
   *
   * Somebody moving a booking is usually moving it a little — a day either way, the same week —
   * so the times they want are next to the ones they have. Clamped to today because the engine
   * will not offer the past, and an appointment already under way would otherwise open on a window
   * with nothing in it.
   */
  const current = toBusinessDate(appointment.startsAt, appointment.timezone);
  const [windowStart, setWindowStart] = useState<IsoDate>(current < today ? today : current);
  const [chosenDate, setChosenDate] = useState<IsoDate | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [refreshes, setRefreshes] = useState(0);
  const [moving, setMoving] = useState(false);
  const [staleGrid, setStaleGrid] = useState(false);

  const path = manageAvailabilityPath({
    token,
    from: windowStart,
    to: addIsoDays(windowStart, FORTNIGHT_DAYS - 1),
  });

  async function onMove() {
    if (!selection) return;
    setMoving(true);
    setStaleGrid(false);
    try {
      await onMoved(
        await publicApi.reschedule(appointment.id, {
          authority: { manageToken: token },
          startsAt: selection.startsAt,
          // Off the Slot rather than omitted. The wire treats absent as "keep the current person",
          // which would be the wrong answer for every grid this page draws: they are all "anyone
          // eligible" grids, so the time the Customer pressed may well belong to somebody else and
          // is the only person they can be said to have agreed to.
          employeeId: selection.employeeId,
        }),
      );
    } catch (cause) {
      if (!(cause instanceof ApiError)) {
        await onFailed(
          new ApiError({
            code: 'INTERNAL_ERROR',
            message: 'The appointment could not be moved. Please try again.',
            status: 0,
          }),
        );
        return;
      }
      await onFailed(cause);
      if (isStaleSlot(cause.code)) {
        // The times on screen were computed against a world that has since moved — most obviously
        // somebody taking the slot in between. Re-ask rather than leaving a dead list up, and drop
        // the choice so the same doomed button cannot be pressed again. The card stays open: the
        // Customer still wants to move their appointment, and closing it would make them start over.
        setSelection(null);
        setStaleGrid(true);
        setRefreshes((count) => count + 1);
      }
    } finally {
      setMoving(false);
    }
  }

  return (
    <Card>
      <CardHeader
        title="Move this appointment"
        description="Your confirmation code does not change, and the price you agreed stays as it is."
      />

      {staleGrid && (
        <p
          role="alert"
          className="border-warning/40 bg-warning/5 text-ink mb-4 rounded-md border px-3 py-2 text-sm leading-relaxed"
        >
          The times below have been recalculated — choose one of those. Your appointment has not
          moved and is still at its original time.
        </p>
      )}

      <FortnightPicker
        key={`${path}#${refreshes}`}
        today={today}
        windowStart={windowStart}
        onPage={(nextStart) => {
          setWindowStart(nextStart);
          setChosenDate(null);
          setSelection(null);
        }}
        path={path}
        service={appointment.service}
        // Every grid here is asked without an `employeeId`, so the empty copy is always the
        // "nobody is working" wording rather than a named person's. Passing the current
        // Employee's name would word the refusal about somebody the question was not about.
        employeeName={null}
        chosenDate={chosenDate}
        onChooseDate={(date) => {
          setChosenDate(date);
          setSelection(null);
        }}
        selectedStartsAt={selection?.startsAt ?? null}
        onSelectSlot={(slot: AvailableSlot, timezone) =>
          setSelection({
            startsAt: slot.startsAt,
            employeeId: slot.employee.id,
            employeeName: slot.employee.fullName,
            timezone,
          })
        }
      />

      {/*
        No note here about the Customer's own time being missing from the grid, because it is not
        missing — this endpoint excludes the appointment being moved, so the hour it currently
        occupies is offered straight back. The dashboard's reschedule screen does carry that note,
        and it is right to: it reads the *unexcluded* endpoint, where the appointment blocks itself
        and the gap does look like a defect. Copying the sentence across was the obvious mistake and
        it was made once — the two screens ask different endpoints precisely so that this one does
        not need it.
      */}

      <div className="mt-5 flex flex-col gap-3">
        {selection && (
          <p className="text-ink-muted text-sm">
            Moving to{' '}
            <span className="text-ink font-medium">
              {formatIsoDate(toBusinessDate(selection.startsAt, selection.timezone))}
            </span>{' '}
            at{' '}
            <span className="tabular-nums">
              {formatTime(selection.startsAt, selection.timezone)}
            </span>
            {/* Always named, not only when the person changes: this page cannot tell whether it is
                the same one, because the appointment it holds carries a name and no id. */}
            , with {selection.employeeName}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button loading={moving} disabled={!selection} onClick={() => void onMove()}>
            Move appointment
          </Button>
          <Button variant="secondary" onClick={onDone} disabled={moving}>
            Keep the current time
          </Button>
        </div>
      </div>
    </Card>
  );
}
