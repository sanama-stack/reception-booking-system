import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PROFILE } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { ReceptionistSwitch } from './receptionist-switch';

/**
 * A refusal that has to cross a unit boundary to reach its field.
 *
 * The owner types the daily limit in whole currency; the wire carries
 * `aiDailyCostCapCents`. So the server's field name and the input's label describe the same
 * thing in different units, and the screen has to know that to put the message in the right
 * place. It is the only field on this form the server can complain about, which is why the
 * banner is keyed on that one name rather than on `fieldErrors` being empty.
 */

function switchPanel() {
  renderWithToasts(<ReceptionistSwitch profile={PROFILE} onSaved={vi.fn()} />);
}

/** Only changed settings are sent, so every case has to move the limit first. */
async function raiseTheCapAndSave(): Promise<void> {
  const cap = screen.getByLabelText('Daily limit');
  await userEvent.clear(cap);
  await userEvent.type(cap, '99');
  await userEvent.click(screen.getByRole('button', { name: 'Save receptionist settings' }));
}

describe('ReceptionistSwitch, refused', () => {
  it('puts a cap refusal on the Daily limit field, in the units the owner typed', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'aiDailyCostCapCents', message: 'The daily limit cannot exceed $50.' }],
      },
    });
    switchPanel();

    await raiseTheCapAndSave();

    expect(await screen.findByText('The daily limit cannot exceed $50.')).toBeVisible();
    expect(screen.getByLabelText('Daily limit')).toHaveAccessibleDescription(
      'The daily limit cannot exceed $50.',
    );
    // Shown once, beside the field, not also above the form.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('banners a refusal that names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 503,
        code: 'AI_UNAVAILABLE',
        detail: 'The receptionist cannot be switched on right now.',
      },
    });
    switchPanel();

    await raiseTheCapAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The receptionist cannot be switched on right now.',
    );
  });

  it('lets the owner try again rather than leaving the button spinning', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    switchPanel();

    await raiseTheCapAndSave();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Save receptionist settings' })).toBeEnabled();
  });
});
