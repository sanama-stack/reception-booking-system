'use client';

import { useState } from 'react';
import { Button, Card, CardHeader, cn, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import {
  DAYS,
  businessApi,
  type DayOfWeek,
  type HoursInterval,
  type WallClockTime,
  type WeekHours,
} from '@/lib/business';

interface Interval {
  opensAt: WallClockTime;
  closesAt: WallClockTime;
}

interface DayDraft {
  day: DayOfWeek;
  /** Empty means closed. Absence is the representation on the wire too — there is no closed flag. */
  intervals: Interval[];
}

/** What an owner most likely means by "open on this day", offered when they reopen one. */
const DEFAULT_INTERVAL: Interval = { opensAt: '09:00', closesAt: '17:00' };

/**
 * Always seven entries in day order, whatever the server sent.
 *
 * A day with no row is closed, so the week has to be reconstructed rather than mapped: the payload
 * for a business open only on Saturday is one interval long, and the editor still has to show the
 * other six days as closed rather than not showing them at all.
 */
function toDraft(week: WeekHours): DayDraft[] {
  return DAYS.map(({ value }) => ({
    day: value,
    intervals: week.hours
      .filter((entry) => entry.dayOfWeek === value)
      .map((entry) => ({ opensAt: entry.opensAt, closesAt: entry.closesAt })),
  }));
}

/** `hours[2].opensAt` — the key the server uses to name the row that caused a message. */
function fieldKey(index: number, field: keyof Interval): string {
  return `hours[${index}].${field}`;
}

export function HoursEditor({
  week,
  onSaved,
}: {
  week: WeekHours;
  onSaved: (updated: WeekHours) => void;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<DayDraft[]>(() => toDraft(week));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  /**
   * Where each interval ended up in the array that was submitted.
   *
   * The server names a failure by its position in that array — `hours[2].opensAt` — which is not a
   * position in this editor: a business closed on Monday and Tuesday submits Wednesday as index 0.
   * Keeping the mapping from the submission is what puts the message on the row that caused it.
   */
  const [submitted, setSubmitted] = useState<Map<string, number>>(new Map());

  /** Any edit invalidates the mapping the messages are keyed against, so they go with it. */
  function edit(next: (current: DayDraft[]) => DayDraft[]) {
    setDraft(next);
    setError(null);
    setLocalErrors({});
  }

  function updateDay(day: DayOfWeek, intervals: Interval[]) {
    edit((current) =>
      current.map((entry) => (entry.day === day ? { ...entry, intervals } : entry)),
    );
  }

  function copyToAllDays(day: DayOfWeek, intervals: Interval[]) {
    edit((current) =>
      current.map((entry) => ({ ...entry, intervals: intervals.map((i) => ({ ...i })) })),
    );
    const label = DAYS.find((entry) => entry.value === day)?.label ?? '';
    toast(
      intervals.length === 0
        ? `Every day is now closed, like ${label}. Save to keep it.`
        : `Every day now matches ${label}. Save to keep it.`,
      'info',
    );
  }

  function flatten(): { payload: HoursInterval[]; positions: Map<string, number> } {
    const payload: HoursInterval[] = [];
    const positions = new Map<string, number>();
    for (const entry of draft) {
      entry.intervals.forEach((interval, index) => {
        positions.set(`${entry.day}:${index}`, payload.length);
        payload.push({ dayOfWeek: entry.day, ...interval });
      });
    }
    return { payload, positions };
  }

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const { payload, positions } = flatten();

    // The only rule checked here. Everything else — overlap, ordering, a day out of range — is the
    // server's, stated once in `BusinessHoursService.validateWeek` and reported per row. An empty
    // time is the exception: it cannot be sent for the server to reject, because `""` is not a
    // time and the body would fail to parse into a message about JSON rather than about a field.
    const blanks: Record<string, string> = {};
    for (const entry of draft) {
      entry.intervals.forEach((interval, index) => {
        if (!interval.opensAt) blanks[`${entry.day}:${index}:opensAt`] = 'Enter an opening time.';
        if (!interval.closesAt) blanks[`${entry.day}:${index}:closesAt`] = 'Enter a closing time.';
      });
    }
    if (Object.keys(blanks).length > 0) {
      setLocalErrors(blanks);
      setError(null);
      return;
    }

    setSaving(true);
    setError(null);
    setSubmitted(positions);
    try {
      const updated = await businessApi.replaceHours(payload);
      onSaved(updated);
      setDraft(toDraft(updated));
      toast('Your opening hours have been saved.', 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast('The opening hours could not be saved.', 'error');
    } finally {
      setSaving(false);
    }
  }

  function messageFor(day: DayOfWeek, index: number, field: keyof Interval): string | undefined {
    const local = localErrors[`${day}:${index}:${field}`];
    if (local) return local;
    const position = submitted.get(`${day}:${index}`);
    if (position === undefined || !error) return undefined;
    return error.fieldErrors[fieldKey(position, field)];
  }

  // A message the server sent against a row this editor could not find. It must still be shown.
  const placed = new Set(
    draft.flatMap((entry) =>
      entry.intervals.flatMap((_, index) => {
        const position = submitted.get(`${entry.day}:${index}`);
        return position === undefined
          ? []
          : [fieldKey(position, 'opensAt'), fieldKey(position, 'closesAt')];
      }),
    ),
  );
  const unplaced = error
    ? Object.entries(error.fieldErrors).filter(([field]) => !placed.has(field))
    : [];
  const showBanner =
    error !== null && (Object.keys(error.fieldErrors).length === 0 || unplaced.length > 0);

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-6" noValidate>
      {showBanner && error && (
        <div
          role="alert"
          className="border-danger/30 bg-danger/5 text-ink rounded-md border px-3 py-2 text-sm"
        >
          <p>{error.message}</p>
          {unplaced.map(([field, message]) => (
            <p key={field} className="text-ink-muted mt-1">
              {message}
            </p>
          ))}
        </div>
      )}

      <Card>
        <CardHeader
          title="Opening hours"
          description={`The week as customers see it. All times are in ${week.timezone}.`}
        />

        <ul className="flex flex-col">
          {draft.map((entry) => {
            const closed = entry.intervals.length === 0;
            const label = DAYS.find((day) => day.value === entry.day)?.label ?? '';

            return (
              <li
                key={entry.day}
                className="border-border flex flex-col gap-3 border-b py-4 first:pt-0 last:border-b-0 last:pb-0 sm:flex-row sm:items-start sm:gap-6"
              >
                <div className="flex shrink-0 items-center gap-3 sm:w-40 sm:pt-2">
                  <span className="text-ink text-sm font-medium">{label}</span>
                  {closed && (
                    <span className="text-ink-muted bg-surface-muted rounded px-1.5 py-0.5 text-xs">
                      Closed
                    </span>
                  )}
                </div>

                <div className="flex min-w-0 flex-1 flex-col gap-3">
                  {closed ? (
                    <p className="text-ink-muted text-sm sm:pt-2">
                      Closed all day. No bookings can be taken.
                    </p>
                  ) : (
                    entry.intervals.map((interval, index) => (
                      <div key={index} className="flex flex-col gap-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <TimeField
                            label={`${label} interval ${index + 1} opens at`}
                            value={interval.opensAt}
                            invalid={messageFor(entry.day, index, 'opensAt') !== undefined}
                            onChange={(value) =>
                              updateDay(
                                entry.day,
                                entry.intervals.map((current, i) =>
                                  i === index ? { ...current, opensAt: value } : current,
                                ),
                              )
                            }
                          />
                          <span className="text-ink-muted text-sm">to</span>
                          <TimeField
                            label={`${label} interval ${index + 1} closes at`}
                            value={interval.closesAt}
                            invalid={messageFor(entry.day, index, 'closesAt') !== undefined}
                            onChange={(value) =>
                              updateDay(
                                entry.day,
                                entry.intervals.map((current, i) =>
                                  i === index ? { ...current, closesAt: value } : current,
                                ),
                              )
                            }
                          />
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() =>
                              updateDay(
                                entry.day,
                                entry.intervals.filter((_, i) => i !== index),
                              )
                            }
                          >
                            Remove
                          </Button>
                        </div>
                        {(['opensAt', 'closesAt'] as const).map((field) => {
                          const message = messageFor(entry.day, index, field);
                          return message ? (
                            <p key={field} role="alert" className="text-danger text-sm">
                              {message}
                            </p>
                          ) : null;
                        })}
                      </div>
                    ))
                  )}

                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      onClick={() =>
                        updateDay(entry.day, [...entry.intervals, { ...DEFAULT_INTERVAL }])
                      }
                    >
                      {closed ? 'Open this day' : 'Add another interval'}
                    </Button>
                    {!closed && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => updateDay(entry.day, [])}
                      >
                        Mark closed
                      </Button>
                    )}
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => copyToAllDays(entry.day, entry.intervals)}
                    >
                      Copy to all days
                    </Button>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      </Card>

      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" loading={saving}>
          Save opening hours
        </Button>
        <p className="text-ink-muted text-sm">
          The whole week is saved at once. Two intervals on one day make a split shift — 09:00 to
          13:00 and 14:00 to 18:00 is a lunch break.
        </p>
      </div>
    </form>
  );
}

/** A bare `<input type="time">`: the label belongs to the row, so only the assistive one is here. */
function TimeField({
  label,
  value,
  invalid,
  onChange,
}: {
  label: string;
  value: WallClockTime;
  invalid: boolean;
  onChange: (value: WallClockTime) => void;
}) {
  return (
    <input
      type="time"
      aria-label={label}
      aria-invalid={invalid || undefined}
      value={value}
      onChange={(event) => onChange(event.target.value)}
      className={cn(
        'border-border bg-surface text-ink h-10 rounded-md border px-3 text-sm',
        invalid && 'border-danger',
      )}
    />
  );
}
