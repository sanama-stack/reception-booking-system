import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderScreen, serve } from '@/test/harness';
import { RegisterForm } from './register-form';

/**
 * The other write a stranger makes, and the one that can be refused four ways at once.
 *
 * Sibling of `login-form.test.tsx` and deliberately not a copy of it: this form's failure is
 * *fielded*. The server can refuse the address, the name and the password in one reply, and the
 * screen's rule is that a message with a field goes beside that field and only a message without
 * one goes to the banner. Asserting the banner alone would leave the branch that routes them
 * untested, which is the branch with four ways to be wrong.
 */

async function register(): Promise<void> {
  await userEvent.type(screen.getByLabelText('Business name'), 'Salon Aria');
  await userEvent.type(screen.getByLabelText('Your name'), 'Nino Beridze');
  await userEvent.type(screen.getByLabelText('Email'), 'owner@example.com');
  await userEvent.type(screen.getByLabelText('Password'), 'long-enough-password');
  await userEvent.click(screen.getByRole('button', { name: 'Create business' }));
}

describe('RegisterForm, refused', () => {
  it('shows the message the server sent when the refusal names no field', async () => {
    serve({
      kind: 'failing',
      status: 503,
      code: 'INTERNAL_ERROR',
      detail: 'Registration is temporarily unavailable.',
    });
    renderScreen(<RegisterForm />);

    await register();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Registration is temporarily unavailable.',
    );
  });

  /**
   * The reply that refuses everything at once.
   *
   * Each message has to reach its own field: a summary carrying all four would make the person
   * work out which input each sentence was about, and the server already said.
   */
  it('puts each fielded message against the field it names', async () => {
    serve({
      kind: 'failing',
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'The request could not be completed.',
      errors: [
        { field: 'businessName', message: 'That name is already taken.' },
        { field: 'fullName', message: 'Enter your full name.' },
        { field: 'email', message: 'That email is already registered.' },
        { field: 'password', message: 'Choose a longer password.' },
      ],
    });
    renderScreen(<RegisterForm />);

    await register();

    expect(await screen.findByText('That email is already registered.')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'That email is already registered.',
    );
    expect(screen.getByLabelText('Business name')).toHaveAccessibleDescription(
      'That name is already taken.',
    );
    expect(screen.getByLabelText('Your name')).toHaveAccessibleDescription('Enter your full name.');
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription(
      'Choose a longer password.',
    );
  });

  /**
   * The banner is for what has nowhere else to go.
   *
   * `detail` is sent on a fielded refusal too — the server always sends one — so a screen that
   * banners it as well would show a generic sentence above four specific ones every time.
   */
  it('keeps the banner away when every message already has a field', async () => {
    serve({
      kind: 'failing',
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'The request could not be completed.',
      errors: [{ field: 'email', message: 'That email is already registered.' }],
    });
    renderScreen(<RegisterForm />);

    await register();

    await screen.findByText('That email is already registered.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('lets the visitor try again rather than leaving the button spinning', async () => {
    serve({ kind: 'failing', status: 409, code: 'EMAIL_TAKEN' });
    renderScreen(<RegisterForm />);

    await register();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Create business' })).toBeEnabled();
  });

  /**
   * The case this form, like the login form, used to fail silently.
   *
   * Both answer a non-`ApiError` cause with `null`, which renders nothing at all. `client.ts` now
   * rejects an unreadable success body as an `ApiError`, so the branch is unreachable — and this
   * is the assertion that says so from the screen rather than from the client.
   */
  it('says something when the server answers with something it cannot read', async () => {
    serve({ kind: 'unreadable' });
    renderScreen(<RegisterForm />);

    await register();

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not read/i);
  });
});
