import userEvent from '@testing-library/user-event';
import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { router } from '@/test/navigation';
import { renderScreen, serve } from '@/test/harness';
import { useSession } from './session-context';

/**
 * The one write in the catalogue that is supposed to show **nothing**, and why that is not the
 * same as doing nothing.
 *
 * Signing out ends the local session whether or not the server heard about it: the token is gone
 * from this browser either way, and a banner saying "sign-out failed" would be false — it did not
 * fail, it just was not acknowledged. So there is no message to assert. The `finally` is the
 * whole contract, and the redirect is the only thing it leaves behind that a test can hold.
 *
 * That made this entry untestable until `test/navigation.ts` held one router for the suite
 * instead of building a fresh set of spies on every `useRouter()` call — see the note there.
 *
 * **The risk being guarded is silent regression into the obvious shape.** Wrapping the request in
 * a `try`/`catch` that only reports, or moving the clear-and-redirect into the `try`, both look
 * like tidying and both leave a signed-out person on a dashboard holding a dead session.
 */

function SignOutButton() {
  const { signOut, status } = useSession();
  return (
    <>
      <p data-testid="status">{status}</p>
      {/* The promise rejects when the server refuses — that is the caller's to swallow, and a
          rejection escaping into the test would fail it for the wrong reason. */}
      <button onClick={() => void signOut().catch(() => {})}>Sign out</button>
    </>
  );
}

describe('signing out when the server refuses', () => {
  it('ends the local session anyway', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderScreen(<SignOutButton />);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated'));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    // 'anonymous', which is this module's word for it — not 'unauthenticated'.
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('anonymous'));
  });

  it('still sends them to the sign-in page', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderScreen(<SignOutButton />);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated'));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login'));
  });

  /** Deliberate. There is nothing true to say, so the screen says nothing. */
  it('says nothing about it', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderScreen(<SignOutButton />);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('authenticated'));
    await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/login'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
