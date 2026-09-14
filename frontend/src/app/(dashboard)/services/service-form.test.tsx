import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EMPLOYEE, SERVICE } from '@/test/fixtures';
import { renderScreen, serve } from '@/test/harness';
import { ServiceForm } from './service-form';

/**
 * The halfway state — the only entry in the write catalogue whose failure happens *after* a
 * successful write.
 *
 * Creating a service cannot carry its assignments: the employee ids belong to a service that did
 * not exist a moment ago, so it is two writes. If the second fails the service **is still there**,
 * and the form's obligation changes completely — it cannot say "could not be saved", because
 * something was, and it must not leave the owner on a create form for a service that now exists.
 *
 * Every other screen in this catalogue can treat a refusal as "nothing happened". This one cannot,
 * and nothing had ever driven it.
 */

const WEEK = { timezone: 'UTC', hours: [] };

function createForm() {
  renderScreen(
    <ServiceForm
      service={null}
      employees={[EMPLOYEE]}
      week={WEEK}
      currency="GEL"
      onSaved={vi.fn()}
    />,
  );
}

async function fillAndSubmit({ assign }: { assign: boolean }): Promise<void> {
  await userEvent.type(screen.getByLabelText('Name'), 'Beard trim');
  const length = screen.getByLabelText('Length (minutes)');
  await userEvent.clear(length);
  await userEvent.type(length, '30');
  // Required by `localProblems`, which refuses the submit before any request is made — and the
  // label carries the currency, because on a create the server has not stamped one yet.
  await userEvent.type(screen.getByLabelText('Price (GEL)'), '25.00');
  if (assign) await userEvent.click(screen.getByRole('checkbox', { name: /Nino Beridze/ }));
  await userEvent.click(screen.getByRole('button', { name: 'Add service' }));
}

describe('ServiceForm, when the create is refused', () => {
  it('banners the server sentence and keeps the owner on the form', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SERVICE_NAME_TAKEN',
        detail: 'You already offer a service with that name.',
      },
    });
    createForm();

    await fillAndSubmit({ assign: false });

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'You already offer a service with that name.',
    );
    expect(screen.getByRole('button', { name: 'Add service' })).toBeEnabled();
  });
});

describe('ServiceForm, when the create works and the assignment does not', () => {
  /**
   * Only the second write is refused. `refusing.path` narrows it to the assignment endpoint, so
   * `POST /services` is answered from the catalogue and `PUT /services/…/employees` is not —
   * which is the whole point of this case and was not expressible before the harness gained it.
   */
  function serveHalfway(detail?: string) {
    serve({
      kind: 'body',
      bodies: { '/services': { ...SERVICE, name: 'Beard trim', employeeIds: [] } },
      refusing: {
        kind: 'failing',
        path: `/services/${SERVICE.id}/employees`,
        status: 409,
        code: 'EMPLOYEE_INACTIVE',
        ...(detail ? { detail } : {}),
      },
    });
  }

  it('says the service was created and what failed, in one sentence', async () => {
    serveHalfway('Nino Beridze is not active.');
    createForm();

    await fillAndSubmit({ assign: true });

    const toast = await screen.findByText(/was created, but who provides it could not be saved/);
    // Both halves matter: that it exists, and the server's reason it is not yet assignable.
    expect(toast).toHaveTextContent('"Beard trim" was created');
    expect(toast).toHaveTextContent('Nino Beridze is not active.');
  });

  /**
   * Not a banner. A banner would sit on a create form for a service that already exists, and the
   * owner's next press would try to create it again.
   */
  it('does not report the halfway state as a failed create', async () => {
    serveHalfway('Nino Beridze is not active.');
    createForm();

    await fillAndSubmit({ assign: true });

    await screen.findByText(/was created, but who provides it could not be saved/);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
