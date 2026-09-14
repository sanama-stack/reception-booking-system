import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SERVICE } from '@/test/fixtures';
import { renderScreen, serve } from '@/test/harness';
import { DeleteService } from './delete-service';

/**
 * The one write in the catalogue whose refusal is the *normal* answer rather than a fault.
 *
 * A service that has ever been booked cannot be deleted, and the server's message names
 * deactivation as what to do instead. So this refusal is not an interruption to be dismissed —
 * it is the reply, it has to stay on the page, and it is the only write here rendered through
 * the shared `ErrorState`.
 *
 * Two consequences point the opposite way from every other dialog in this suite, and both are
 * asserted because both look like bugs if you have read the others first:
 *
 *   - the confirmation **closes** on refusal, where `active-toggle`'s must stay open — because
 *     here the answer appears on the page behind it, and a modal left up would cover it;
 *   - the error **code** is shown, where the sixteen hand-rolled write banners all drop it.
 *     `ErrorState` prints it in monospace, and this is the write that inherits that.
 */

async function deleteIt(): Promise<void> {
  await userEvent.click(screen.getByRole('button', { name: 'Delete service' }));
  const [, confirm] = screen.getAllByRole('button', { name: 'Delete service' });
  await userEvent.click(confirm!);
}

describe('DeleteService, refused', () => {
  function serveRefusal() {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SERVICE_IN_USE',
        detail:
          'This service has been booked before, so it cannot be deleted. Deactivate it instead.',
      },
    });
  }

  it('renders the server message, which is the one that says what to do instead', async () => {
    serveRefusal();
    renderScreen(<DeleteService service={SERVICE} />);

    await deleteIt();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This service has been booked before, so it cannot be deleted. Deactivate it instead.',
    );
  });

  /** The asymmetry recorded in the catalogue: reads print the code, writes drop it. This one keeps it. */
  it('shows the code as well, because ErrorState does', async () => {
    serveRefusal();
    renderScreen(<DeleteService service={SERVICE} />);

    await deleteIt();

    expect(await screen.findByText('SERVICE_IN_USE')).toBeVisible();
  });

  /**
   * The opposite of `active-toggle`, on purpose. There the dialog must stay open because the
   * refusal is a toast; here it must close, because the refusal is behind it.
   */
  it('closes the confirmation so the answer behind it can be read', async () => {
    serveRefusal();
    renderScreen(<DeleteService service={SERVICE} />);

    await deleteIt();

    await screen.findByRole('alert');
    // The dialog's own `open`, not the absence of its text. jsdom implements none of the modal
    // behaviour -- `setup.ts` shims `showModal`/`close` and says plainly that is all it does --
    // so a closed dialog's children are still in the document here. Asserting they had gone
    // would be asserting a browser behaviour this environment does not have, and would pass or
    // fail for reasons unrelated to the screen.
    const dialog = screen.getByText('Delete this service?').closest('dialog');
    expect(dialog).not.toBeNull();
    expect(dialog!.open).toBe(false);
  });

  /** It is the reply, so it stays. A refusal that vanished would leave the owner with no answer. */
  it('leaves the refusal on the page rather than flashing it', async () => {
    serveRefusal();
    renderScreen(<DeleteService service={SERVICE} />);

    await deleteIt();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Delete service' })).toBeEnabled();
    expect(screen.getByRole('alert')).toBeVisible();
  });
});
