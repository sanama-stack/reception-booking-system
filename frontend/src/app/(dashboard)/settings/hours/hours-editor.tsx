'use client';

import { WeekEditor, type WeekEditorCopy, type WeekInterval } from '@/components/week-editor';
import { businessApi, type WeekHours } from '@/lib/business';

/**
 * Opening hours, in the shared seven-day editor.
 *
 * The control itself lives in `components/week-editor`, because an employee's Working Schedule is
 * the same editor with three field names changed. What stays here is everything that is genuinely
 * about *opening hours*: the words, and the translation between this screen's vocabulary
 * (`opensAt`/`closesAt`) and the neutral one the control speaks.
 */
const COPY: Omit<WeekEditorCopy, 'description'> = {
  title: 'Opening hours',
  footnote:
    'The whole week is saved at once. Two intervals on one day make a split shift — 09:00 to 13:00 and 14:00 to 18:00 is a lunch break.',
  submitLabel: 'Save opening hours',
  savedMessage: 'Your opening hours have been saved.',
  failedMessage: 'The opening hours could not be saved.',
  closedBadge: 'Closed',
  closedNote: 'Closed all day. No bookings can be taken.',
  openDay: 'Open this day',
  addInterval: 'Add another interval',
  markClosed: 'Mark closed',
  copyToAll: 'Copy to all days',
  copiedMessage: (day) => `Every day now matches ${day}. Save to keep it.`,
  copiedClosedMessage: (day) => `Every day is now closed, like ${day}. Save to keep it.`,
  intervalNoun: 'interval',
  startLabel: 'opens at',
  endLabel: 'closes at',
  blankStartMessage: 'Enter an opening time.',
  blankEndMessage: 'Enter a closing time.',
};

function toIntervals(week: WeekHours): WeekInterval[] {
  return week.hours.map((entry) => ({
    dayOfWeek: entry.dayOfWeek,
    startsAt: entry.opensAt,
    endsAt: entry.closesAt,
  }));
}

export function HoursEditor({
  week,
  onSaved,
}: {
  week: WeekHours;
  onSaved: (updated: WeekHours) => void;
}) {
  return (
    <WeekEditor
      week={toIntervals(week)}
      copy={{
        ...COPY,
        description: `The week as customers see it. All times are in ${week.timezone}.`,
      }}
      fieldKey={(index, bound) => `hours[${index}].${bound === 'start' ? 'opensAt' : 'closesAt'}`}
      onSave={async (payload) => {
        const updated = await businessApi.replaceHours(
          payload.map((interval) => ({
            dayOfWeek: interval.dayOfWeek,
            opensAt: interval.startsAt,
            closesAt: interval.endsAt,
          })),
        );
        onSaved(updated);
        return toIntervals(updated);
      }}
    />
  );
}
