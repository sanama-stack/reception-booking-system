import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderScreen, serve } from '@/test/harness';
import { LoginForm } from './login-form';

/**
 * The first write a stranger makes, and what it says when it is refused.
 *
 * It is the first of the twenty-eight in `test/screens/catalogue.ts` to be asserted at all. The
 * suite had watched a read fail eight times and a write fail never, which is the blind spot the
 * coverage gate grew a write half to make countable.
 */

async function signIn(): Promise<void> {
  await userEvent.type(screen.getByLabelText('Email'), 'owner@example.com');
  await userEvent.type(screen.getByLabelText('Password'), 'not-the-password');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('LoginForm, refused', () => {
  it('shows the message the server sent rather than one of its own', async () => {
    serve({
      kind: 'failing',
      status: 401,
      code: 'INVALID_CREDENTIALS',
      detail: 'Email or password is incorrect.',
    });
    renderScreen(<LoginForm />);

    await signIn();

    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect.');
  });

  it('lets the visitor try again rather than leaving the button spinning', async () => {
    serve({ kind: 'failing', status: 401, code: 'INVALID_CREDENTIALS' });
    renderScreen(<LoginForm />);

    await signIn();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  /**
   * The case this form used to fail silently.
   *
   * A `200` whose body cannot be read threw a bare `SyntaxError` out of the client, and this
   * form's catch turned anything that was not an `ApiError` into `null` — no banner, no toast, a
   * spinner that stopped and nothing else. `lib/api/client.ts` now makes the promise its callers
   * were already relying on; this is that promise seen from the screen.
   */
  it('says something when the server answers with something it cannot read', async () => {
    serve({ kind: 'unreadable' });
    renderScreen(<LoginForm />);

    await signIn();

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not read/i);
  });
});
