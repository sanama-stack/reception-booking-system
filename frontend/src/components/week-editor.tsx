'use client';

import { useState } from 'react';
import { Button, Card, CardHeader, cn, useToast } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { DAYS, type DayOfWeek, type WallClockTime } from '@/lib/business';

/**
 * One interval of a week, in the neutral vocabulary this control speaks.
 *
 * Opening hours call these `opensAt`/`closesAt` and a Working Schedule calls them
 * `startsAt`/`endsAt`; the difference is three field names on the wire and nothing else, so the
 * translation happens in each caller rather than here.
 */
export interface WeekInterval {
  dayOfWeek: DayOfWeek;
  startsAt: WallClockTime;
  endsAt: WallClockTime;
}

/**
 * Everything this control says out loud.
 *
 * It is a long list, and that is the point: all the *logic* below — the closed-is-empty
 * representation, the index mapping, the flush of stale messages on every edit — is shared, and
 * only the words differ. A `variant: 'hours' | 'schedule'` flag would have put one screen's copy
 * inside a component the other screen also uses.
 */
export interface WeekEditorCopy {
  title: string;
  description: string;
  footnote: string;
  submitLabel: string;
  savedMessage: string;
  failedMessage: string;
  /** Shown on a day with no intervals: a badge beside the day, and a sentence in its place. */
  closedBadge: string;
  closedNote: string;
  openDay: string;
  addInterval: string;
  markClosed: string;
  copyToAll: string;
  copiedMessage: (day: string) => string;
  copiedClosedMessage: (day: string) => string;
  /** `interval` or `shift` — used only in assistive labels, where the row has no visible one. */
  intervalNoun: string;
  startLabel: string;
  endLabel: string;
  blankStartMessage: string;
  blankEndMessage: string;
}

interface Interval {
  startsAt: WallClockTime;
  endsAt: WallClockTime;
}

interface DayDraft {
  day: DayOfWeek;
  /** Empty means closed. Absence is the representation on the wire too — there is no closed flag. */
  intervals: Interval[];
}

/** What an owner most likely means by "works on this day", offered when they open one. */
const DEFAULT_INTERVAL: Interval = { startsAt: '09:00', endsAt: '17:00' };

/**
 * Always seven entries in day order, whatever the server sent.
 *
 * A day with no row is closed, so the week has to be reconstructed rather than mapped: the payload
 * for a business open only on Saturday is one interval long, and the editor still has to show the
 * other six days as closed rather than not showing them at all.
 */
function toDraft(week: WeekInterval[]): DayDraft[] {
  return DAYS.map(({ value }) => ({
    day: value,
    intervals: week
      .filter((entry) => entry.dayOfWeek === value)
      .map((entry) => ({ startsAt: entry.startsAt, endsAt: entry.endsAt })),
  }));
}

/**
 * The seven-day editor behind both `/settings/hours` and an employee's Working Schedule.
 *
 * They are one control on purpose. The two answer different questions — "when are we open" and
 * "when is this person willing to work" — but they are the same *kind* of fact, stored the same
 * way, validated by the same rules, and reported against the same key shape. Phase 05 intersects
 * the two lists; an owner who has learned one editor has learned both.
 *
 * @param onSave performs the write and answers with the week as it now stands. The caller owns the
 *   request because it owns the field names; everything about what a week *is* stays here.
 */
export function WeekEditor({
  week,
  copy,
  fieldKey,
  onSave,
}: {
  week: WeekInterval[];
  copy: WeekEditorCopy;
  /** `hours[2].opensAt`, `schedule[2].startsAt` — how the server names the row that failed. */
  fieldKey: (index: number, bound: 'start' | 'end') => string;
  onSave: (payload: WeekInterval[]) => Promise<WeekInterval[]>;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<DayDraft[]>(() => toDraft(week));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  /**
   * Where each interval ended up in the array that was submitted.
   *
   * The server names a failure by its position in that array — `schedule[2].startsAt` — which is
   * not a position in this editor: a week with no Monday or Tuesday submits Wednesday as index 0.
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
      intervals.length === 0 ? copy.copiedClosedMessage(label) : copy.copiedMessage(label),
      'info',
    );
  }

  function flatten(): { payload: WeekInterval[]; positions: Map<string, number> } {
    const payload: WeekInterval[] = [];
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
    // server's, stated once and reported per row. An empty time is the exception: it cannot be
    // sent for the server to reject, because `""` is not a time and the body would fail to parse
    // into a message about JSON rather than about a field.
    const blanks: Record<string, string> = {};
    for (const entry of draft) {
      entry.intervals.forEach((interval, index) => {
        if (!interval.startsAt) blanks[`${entry.day}:${index}:startsAt`] = copy.blankStartMessage;
        if (!interval.endsAt) blanks[`${entry.day}:${index}:endsAt`] = copy.blankEndMessage;
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
      setDraft(toDraft(await onSave(payload)));
      toast(copy.savedMessage, 'success');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause : null);
      if (!(cause instanceof ApiError)) toast(copy.failedMessage, 'error');
    } finally {
      setSaving(false);
    }
  }

  function messageFor(day: DayOfWeek, index: number, bound: 'start' | 'end'): string | undefined {
    const field = bound === 'start' ? 'startsAt' : 'endsAt';
    const local = localErrors[`${day}:${index}:${field}`];
    if (local) return local;
    const position = submitted.get(`${day}:${index}`);
    if (position === undefined || !error) return undefined;
    return error.fieldErrors[fieldKey(position, bound)];
  }

  // A message the server sent against a row this editor could not find. It must still be shown.
  const placed = new Set(
    draft.flatMap((entry) =>
      entry.intervals.flatMap((_, index) => {
        const position = submitted.get(`${entry.day}:${index}`);
        return position === undefined
          ? []
          : [fieldKey(position, 'start'), fieldKey(position, 'end')];
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
        <CardHeader title={copy.title} description={copy.description} />

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
                      {copy.closedBadge}
                    </span>
                  )}
                </div>

                <div className="flex min-w-0 flex-1 flex-col gap-3">
                  {closed ? (
                    <p className="text-ink-muted text-sm sm:pt-2">{copy.closedNote}</p>
                  ) : (
                    entry.intervals.map((interval, index) => (
                      <div key={index} className="flex flex-col gap-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <TimeField
                            label={`${label} ${copy.intervalNoun} ${index + 1} ${copy.startLabel}`}
                            value={interval.startsAt}
                            invalid={messageFor(entry.day, index, 'start') !== undefined}
                            onChange={(value) =>
                              updateDay(
                                entry.day,
                                entry.intervals.map((current, i) =>
                                  i === index ? { ...current, startsAt: value } : current,
                                ),
                              )
                            }
                          />
                          <span className="text-ink-muted text-sm">to</span>
                          <TimeField
                            label={`${label} ${copy.intervalNoun} ${index + 1} ${copy.endLabel}`}
                            value={interval.endsAt}
                            invalid={messageFor(entry.day, index, 'end') !== undefined}
                            onChange={(value) =>
                              updateDay(
                                entry.day,
                                entry.intervals.map((current, i) =>
                                  i === index ? { ...current, endsAt: value } : current,
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
                        {(['start', 'end'] as const).map((bound) => {
                          const message = messageFor(entry.day, index, bound);
                          return message ? (
                            <p key={bound} role="alert" className="text-danger text-sm">
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
                      {closed ? copy.openDay : copy.addInterval}
                    </Button>
                    {!closed && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => updateDay(entry.day, [])}
                      >
                        {copy.markClosed}
                      </Button>
                    )}
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => copyToAllDays(entry.day, entry.intervals)}
                    >
                      {copy.copyToAll}
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
          {copy.submitLabel}
        </Button>
        <p className="text-ink-muted text-sm">{copy.footnote}</p>
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
