import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PROFILE } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { BookingForm } from './booking-form';

/**
 * The five policy numbers that decide what the availability engine will offer.
 *
 * Worth asserting beyond the usual split because of what these fields *are*: a refusal that
 * failed to reach the owner would leave the booking page running on the old policy while the
 * settings screen implies the new one. The rest of the application reads these values, so a
 * silently-unsaved change here is not a form annoyance — it is the engine and the screen
 * disagreeing about what the business does.
 */

function bookingSettings() {
  renderWithToasts(<BookingForm profile={PROFILE} onSaved={vi.fn()} />);
}

/** Only changed settings are sent, so each case moves the notice period first. */
async function changeNoticeAndSave(): Promise<void> {
  const notice = screen.getByLabelText('Minimum notice');
  await userEvent.clear(notice);
  await userEvent.type(notice, '120');
  await userEvent.click(screen.getByRole('button', { name: 'Save booking settings' }));
}

describe('BookingForm, refused', () => {
  it('shows the server sentence when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The booking settings could not be saved.',
      },
    });
    bookingSettings();

    await changeNoticeAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The booking settings could not be saved.',
    );
  });

  it('puts a fielded message against the policy input it names, and no banner', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'minLeadTimeMinutes', message: 'Notice cannot be longer than a day.' }],
      },
    });
    bookingSettings();

    await changeNoticeAndSave();

    expect(screen.getByLabelText('Minimum notice')).toHaveAccessibleDescription(
      'Notice cannot be longer than a day.',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /**
   * The engine reads fields this form does not draw — `timezone`, for one. A message about any
   * of them must reach the banner rather than vanish into a form that cannot show it.
   */
  it('banners a message for a field this form does not draw', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'timezone', message: 'That timezone is no longer recognised.' }],
      },
    });
    bookingSettings();

    await changeNoticeAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('Some of that could not be saved.');
  });

  /** The typed value stays, because it is what the message is about. */
  it('keeps the changed setting on screen so it can be corrected', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    bookingSettings();

    await changeNoticeAndSave();

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Minimum notice')).toHaveValue(120);
  });
});
