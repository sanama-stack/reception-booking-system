'use client';

import { WeekEditor, type WeekEditorCopy, type WeekInterval } from '@/components/week-editor';
import { employeeApi, type WeekSchedule } from '@/lib/staff';

/**
 * A Working Schedule, in the same seven-day editor as the opening hours.
 *
 * Deliberately the same control: an owner who has set their opening hours has already learned this
 * screen. The only differences on the wire are three field names — `startsAt`/`endsAt` rather than
 * `opensAt`/`closesAt` — and the words below.
 *
 * A schedule is stored exactly as given, even where it is wider than the business's opening hours.
 * "When is this person willing to work" and "when are we open" are different facts; phase 05
 * intersects them, and storing only the intersection would silently re-cut everyone's week the
 * next time the opening hours moved.
 */
const COPY: Omit<WeekEditorCopy, 'description'> = {
  title: 'Working schedule',
  footnote:
    'The whole week is saved at once. This can be wider than your opening hours — appointments are only offered where the two overlap.',
  submitLabel: 'Save working schedule',
  savedMessage: 'The working schedule has been saved.',
  failedMessage: 'The working schedule could not be saved.',
  closedBadge: 'Not working',
  closedNote: 'Not working this day. No appointments can be booked.',
  openDay: 'Add a working day',
  addInterval: 'Add another shift',
  markClosed: 'Mark as not working',
  copyToAll: 'Copy to all days',
  copiedMessage: (day) => `Every day now matches ${day}. Save to keep it.`,
  copiedClosedMessage: (day) => `Every day is now a non-working day, like ${day}. Save to keep it.`,
  intervalNoun: 'shift',
  startLabel: 'starts at',
  endLabel: 'ends at',
  blankStartMessage: 'Enter a start time.',
  blankEndMessage: 'Enter an end time.',
};

function toIntervals(week: WeekSchedule): WeekInterval[] {
  return week.schedule.map((entry) => ({
    dayOfWeek: entry.dayOfWeek,
    startsAt: entry.startsAt,
    endsAt: entry.endsAt,
  }));
}

export function ScheduleSection({
  employeeId,
  name,
  week,
  onSaved,
}: {
  employeeId: string;
  name: string;
  week: WeekSchedule;
  onSaved: (updated: WeekSchedule) => void;
}) {
  return (
    <WeekEditor
      week={toIntervals(week)}
      copy={{
        ...COPY,
        description: `When ${name} works. All times are in ${week.timezone}.`,
      }}
      fieldKey={(index, bound) => `schedule[${index}].${bound === 'start' ? 'startsAt' : 'endsAt'}`}
      onSave={async (payload) => {
        const updated = await employeeApi.replaceSchedule(employeeId, payload);
        onSaved(updated);
        return toIntervals(updated);
      }}
    />
  );
}
