import userEvent from '@testing-library/user-event';
import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { WeekHours } from '@/lib/business';
import { renderScreen, serve } from '@/test/harness';
import { HoursEditor } from './hours-editor';

/**
 * The refusal that has to be *translated* before it can be shown.
 *
 * The shared `components/week-editor.tsx` submits only the days that are open, so the array the
 * server validates is not the week on screen: with Monday closed, Wednesday is `hours[0]`. The
 * server names a failure by that position — `hours[0].opensAt` — and the editor keeps a map from
 * the submission back to the row that produced it. Get that wrong and the message lands on the
 * wrong day, which is worse than not showing it: the owner corrects a row the server never
 * complained about.
 *
 * Nothing had tested the editor, so neither of the two screens built on it had a test either.
 * This drives it through the real opening-hours screen rather than the control directly, so the
 * `fieldKey` that screen supplies (`hours[i].opensAt`) is part of what is checked.
 */

/** Monday closed on purpose: it is what makes the row index and the array index disagree. */
const WEEK: WeekHours = {
  timezone: 'UTC',
  hours: [
    { id: 'h-wed', dayOfWeek: 3, opensAt: '09:00', closesAt: '17:00' },
    { id: 'h-thu', dayOfWeek: 4, opensAt: '09:00', closesAt: '17:00' },
  ],
};

function editor() {
  renderScreen(<HoursEditor week={WEEK} onSaved={vi.fn()} />);
}

async function save(): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: 'Save opening hours' }));
}

function dayRow(label: string): HTMLElement {
  const row = screen.getByText(label).closest('li');
  expect(row).not.toBeNull();
  return row as HTMLElement;
}

describe('HoursEditor, refused', () => {
  /**
   * Both positions at once, which is what makes this discriminating.
   *
   * `hours[0]` alone cannot separate the two mappings: for the first open day the row index and
   * the submitted position are both 0 whichever rule you use, so a naive `fieldKey(index, …)`
   * passes it. `hours[1]` is Thursday's submitted position and Thursday's own interval index is
   * 0 — they disagree, and only the correct mapping puts the message on Thursday. Sending both
   * in one refusal also checks they do not collide on the same row.
   */
  it('places each message on the day that produced it, not the row of the same number', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        errors: [
          { field: 'hours[0].opensAt', message: 'Opening time must be before closing.' },
          { field: 'hours[1].closesAt', message: 'Closing time is outside the day.' },
        ],
      },
    });
    editor();

    await save();

    // Wednesday is hours[0]: Monday and Tuesday are closed and were never submitted.
    expect(
      await within(dayRow('Wednesday')).findByText('Opening time must be before closing.'),
    ).toBeVisible();
    // Thursday is hours[1] — and is its own day's interval 0, which is the whole point.
    expect(within(dayRow('Thursday')).getByText('Closing time is outside the day.')).toBeVisible();

    // Neither strays onto a closed day or onto each other's row.
    expect(
      within(dayRow('Monday')).queryByText('Opening time must be before closing.'),
    ).not.toBeInTheDocument();
    expect(
      within(dayRow('Wednesday')).queryByText('Closing time is outside the day.'),
    ).not.toBeInTheDocument();
  });

  /**
   * The same invariant `profile-form` has, and the editor's own comment states it: a message the
   * server sent against a row this editor cannot find must still be shown. `hours[9]` names a
   * position that was never submitted.
   */
  it('banners a message it cannot place on any row', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'hours[9].opensAt', message: 'That interval is not valid.' }],
      },
    });
    editor();

    await save();

    // Both halves: the server's own sentence, and the message that had nowhere to land. Asserting
    // either alone would pass while the other silently vanished.
    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent('Some of that could not be saved.');
    expect(banner).toHaveTextContent('That interval is not valid.');
  });

  it('shows the server sentence when the refusal names no field at all', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'HOURS_OVERLAP',
        detail: 'Two intervals on the same day overlap.',
      },
    });
    editor();

    await save();

    expect(await screen.findByText('Two intervals on the same day overlap.')).toBeVisible();
  });
});
