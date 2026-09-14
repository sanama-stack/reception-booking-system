import userEvent from '@testing-library/user-event';
import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { WeekSchedule } from '@/lib/staff';
import { renderScreen, serve } from '@/test/harness';
import { ScheduleSection } from './schedule-section';

/**
 * The other screen built on `components/week-editor.tsx`, and the half of its behaviour that is
 * this screen's rather than the control's.
 *
 * The translation from a submitted position back to a row is the editor's, and
 * `settings/hours/hours-editor.test.tsx` is where it is proven against its counterfactual. What
 * belongs here is the part `hours-editor` cannot cover: **this screen supplies a different
 * `fieldKey`.** The server names a working-schedule failure `schedule[i].startsAt`, not
 * `hours[i].opensAt`, and a message keyed the other way lands nowhere at all — it would fall
 * through to the banner and read like a server fault rather than a row to fix.
 */

/** Monday not worked, so the row index and the submitted position disagree from Wednesday on. */
const WEEK: WeekSchedule = {
  timezone: 'UTC',
  schedule: [
    { id: 's-wed', dayOfWeek: 3, startsAt: '10:00', endsAt: '16:00' },
    { id: 's-thu', dayOfWeek: 4, startsAt: '10:00', endsAt: '16:00' },
  ],
};

function section() {
  renderScreen(
    <ScheduleSection employeeId="employee-1" name="Nino Beridze" week={WEEK} onSaved={vi.fn()} />,
  );
}

describe('ScheduleSection, refused', () => {
  it('places a schedule[i] message on the day that produced it', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        errors: [{ field: 'schedule[1].startsAt', message: 'They cannot start before 08:00.' }],
      },
    });
    section();

    await userEvent.click(screen.getByRole('button', { name: 'Save working schedule' }));

    const thursday = screen.getByText('Thursday').closest('li') as HTMLElement;
    expect(await within(thursday).findByText('They cannot start before 08:00.')).toBeVisible();
    // Once, and only on that row. A message this screen can place must not ALSO reach the
    // banner, which is what would happen if `fieldKey` did not match what the server sent.
    // Counted rather than queried by role, because the per-row messages are themselves
    // `role="alert"` — the banner is not the only alert on this screen.
    expect(screen.getAllByText('They cannot start before 08:00.')).toHaveLength(1);
  });

  it('shows the server sentence when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SCHEDULE_OVERLAP',
        detail: 'Two working intervals on the same day overlap.',
      },
    });
    section();

    await userEvent.click(screen.getByRole('button', { name: 'Save working schedule' }));

    expect(await screen.findByText('Two working intervals on the same day overlap.')).toBeVisible();
  });
});
