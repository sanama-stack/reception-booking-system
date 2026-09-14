import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { BusinessProfile } from '@/lib/business';
import { renderScreen, serve } from '@/test/harness';
import { ProfileForm } from './profile-form';

/**
 * The refusals this form routes by hand, and the invariant underneath them.
 *
 * Most screens split a refusal two ways — a field message, or a banner. This one has a third
 * case its own comment names: **a server message must never be silently dropped.** A refusal that
 * names a field this form does not render would vanish under a rule that only asks "does it have
 * a field?", so the banner is gated on whether every part of the message is actually *showing*,
 * not on whether it was fielded.
 *
 * That distinction is invisible to a test that only ever sends a field this form renders, which
 * is why the third case below sends one it does not.
 */

const PROFILE: BusinessProfile = {
  id: 'business-1',
  name: 'Aria Studio',
  slug: 'aria-studio',
  timezone: 'UTC',
  currency: 'GEL',
  description: null,
  addressLine: null,
  city: null,
  country: null,
  phone: null,
  email: null,
  website: null,
  slotIntervalMinutes: 15,
  minLeadTimeMinutes: 60,
  maxAdvanceDays: 60,
  cancellationWindowHours: 24,
  cancellationPolicy: null,
  aiEnabled: false,
  aiAdditionalInfo: null,
  aiDailyCostCapCents: 500,
  bookingUrl: 'https://book.example/aria-studio',
  updatedAt: '2026-09-01T09:00:00Z',
};

/**
 * Only changed fields are sent — `changedFields` — so an untouched form answers "nothing to save"
 * and never reaches the server at all. Every case here has to edit something first.
 */
async function renameAndSave(): Promise<void> {
  const name = screen.getByLabelText('Business name');
  await userEvent.clear(name);
  await userEvent.type(name, 'Aria Salon');
  await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
}

describe('ProfileForm, refused', () => {
  it('shows the message the server sent when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The profile could not be saved.',
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('The profile could not be saved.');
  });

  /**
   * `SLUG_TAKEN` is a conflict rather than a shape violation, so it arrives with no `errors`
   * entry at all — and the form puts it on the slug field anyway, because that is where the owner
   * just typed. A rule keyed purely on `fieldErrors` would banner it instead.
   */
  it('places SLUG_TAKEN on the address field although it names none', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SLUG_TAKEN',
        detail: 'That booking page address is already in use.',
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByText('That booking page address is already in use.')).toBeVisible();
    expect(screen.getByLabelText('Booking page address')).toHaveAccessibleDescription(
      'That booking page address is already in use.',
    );
    // And not twice: the banner is suppressed precisely because the message is already showing.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /**
   * The case the invariant exists for. `aiDailyCostCapCents` is a real field of the profile and
   * is **not** rendered by this form, so its message has nowhere to land — and must therefore
   * reach the banner rather than disappear.
   */
  it('banners a fielded message for a field this form does not render', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'aiDailyCostCapCents', message: 'The daily cap is too low.' }],
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('Some of that could not be saved.');
  });

  it('keeps the banner away when every message has a field on this form', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'name', message: 'That name is too short.' }],
      },
    });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    expect(await screen.findByText('That name is too short.')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lets the owner try again rather than leaving the button spinning', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderScreen(<ProfileForm profile={PROFILE} onSaved={vi.fn()} />);

    await renameAndSave();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeEnabled();
  });
});
