import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { CustomerDetail } from '@/lib/customers';
import { renderWithToasts, serve } from '@/test/harness';
import { CustomerForm } from './customer-form';

/**
 * Two editable fields, and the same invariant `profile-form` has: a server message must never be
 * silently dropped.
 *
 * This form renders `fullName` and `email` and nothing else — the phone is the identity key and
 * deliberately uneditable — so its banner rule is written against those two names by hand. A
 * refusal naming `phone` therefore has nowhere to land, and must reach the banner rather than
 * vanish. That is the case the obvious rule ("it was fielded, so it is showing") gets wrong, and
 * it is a live possibility here precisely because the server knows a field this screen refuses
 * to draw.
 */

const CUSTOMER: CustomerDetail = {
  id: 'customer-1',
  fullName: 'Clara Classic',
  phone: '+995555000222',
  email: 'clara@example.com',
  totalAppointments: 3,
  lastAppointmentAt: '2026-09-01T09:00:00Z',
  createdAt: '2026-01-01T09:00:00Z',
};

function form() {
  renderWithToasts(<CustomerForm customer={CUSTOMER} onSaved={vi.fn()} />);
}

/** Only changed fields are sent, so each case edits the name first. */
async function renameAndSave(): Promise<void> {
  const name = screen.getByLabelText('Full name');
  await userEvent.clear(name);
  await userEvent.type(name, 'Clara Classical');
  await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
}

describe('CustomerForm, refused', () => {
  it('shows the server sentence when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The customer could not be saved.',
      },
    });
    form();

    await renameAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('The customer could not be saved.');
  });

  it('puts a fielded message against its field, and no banner', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'email', message: 'That address is not valid.' }],
      },
    });
    form();

    await renameAndSave();

    expect(await screen.findByText('That address is not valid.')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /**
   * The case the invariant exists for. `phone` is a real field of a customer and this form does
   * not render it, so a message about it has nowhere to go but the banner.
   */
  it('banners a message for a field this form refuses to draw', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'phone', message: 'That number belongs to somebody else.' }],
      },
    });
    form();

    await renameAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('Some of that could not be saved.');
  });
});
