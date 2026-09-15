import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PROFILE } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { ReceptionistNotes } from './receptionist-notes';

/**
 * The notes the Receptionist is given, and what the owner sees when saving them is refused.
 *
 * One field, so the split is as simple as it gets — a message naming `aiAdditionalInfo` belongs
 * against the box, anything else above it. Worth asserting anyway: this is the text the model is
 * handed verbatim, and a refusal that disappeared would leave the owner believing the
 * Receptionist had been told something it had not.
 */

function notes() {
  renderWithToasts(<ReceptionistNotes profile={PROFILE} onSaved={vi.fn()} />);
}

/** Save is disabled until something changes, so every case types first. */
async function writeAndSave(): Promise<void> {
  await userEvent.type(
    screen.getByLabelText('Notes for the Receptionist'),
    'We do not take card payments.',
  );
  await userEvent.click(screen.getByRole('button', { name: /^Save/ }));
}

describe('ReceptionistNotes, refused', () => {
  it('shows the server sentence when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 500,
        code: 'INTERNAL_ERROR',
        detail: 'The notes could not be saved.',
      },
    });
    notes();

    await writeAndSave();

    expect(await screen.findByRole('alert')).toHaveTextContent('The notes could not be saved.');
  });

  it('puts a fielded message against the box, and no banner', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'aiAdditionalInfo', message: 'That is longer than the model can hold.' }],
      },
    });
    notes();

    await writeAndSave();

    expect(await screen.findByText('That is longer than the model can hold.')).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /** The text stays, because it is what the owner is being asked to shorten. */
  it('keeps what was typed so the owner can edit rather than rewrite it', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    notes();

    await writeAndSave();

    await screen.findByRole('alert');
    expect(screen.getByLabelText('Notes for the Receptionist')).toHaveValue(
      'We do not take card payments.',
    );
  });
});
