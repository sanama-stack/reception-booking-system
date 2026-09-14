import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SERVICE } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { ServicesScreen } from './services-screen';

/**
 * The empty state of the screen a new owner sees first — docs/09-phase-plan.md §5, rule 7.
 *
 * This screen takes its list as a prop: the page fetches, so loading and error belong to the page
 * and are covered by the gate every screen shares (`components/ui/resource-gate.test.tsx`). What
 * is this screen's own is the sentence it shows when there is nothing, and the way out it offers.
 */
describe('ServicesScreen, with nothing in it', () => {
  it('explains what a service is and offers the way to make one', () => {
    render(<ServicesScreen list={{ services: [] }} onChanged={() => Promise.resolve()} />);

    expect(screen.getByText('No services yet')).toBeInTheDocument();
    expect(screen.getByText(/Nothing can be booked until there is at least one/)).toBeVisible();
    expect(screen.getByRole('link', { name: 'Add your first service' })).toHaveAttribute(
      'href',
      '/services/new',
    );
  });

  it('draws no table at all rather than an empty one', () => {
    render(<ServicesScreen list={{ services: [] }} onChanged={() => Promise.resolve()} />);

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

/**
 * The write this screen owns, and the two things it promises when the server refuses it.
 *
 * The toggle is `components/active-toggle.tsx`, shared with three other bookability screens, and
 * its refusal is a toast rather than a banner because the row it was about is still on screen and
 * unchanged. Nothing in this suite had ever pressed it — the file above asserts the two empty
 * states and stops — so both halves of the promise were unwatched: that the server's own sentence
 * is what the owner reads, and that the row does not move.
 *
 * Activation is the path tested because it is the unconfirmed one: a deactivation opens a dialog
 * first, which is a second thing to get right and is not this write.
 */
describe('ServicesScreen, when the toggle is refused', () => {
  const INACTIVE = { services: [{ ...SERVICE, active: false }] };

  it('toasts the server sentence rather than one of its own', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SERVICE_HAS_NO_EMPLOYEES',
        detail: 'Assign someone who can perform this service before activating it.',
      },
    });
    renderWithToasts(<ServicesScreen list={INACTIVE} onChanged={() => Promise.resolve()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Activate' }));

    expect(
      await screen.findByText('Assign someone who can perform this service before activating it.'),
    ).toBeInTheDocument();
  });

  /**
   * The row must not move. A toggle that flips optimistically and then fails tells the owner the
   * service is bookable when the server has just said it is not — and this list is the only place
   * they would check.
   */
  it('leaves the toggle where it was', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderWithToasts(<ServicesScreen list={INACTIVE} onChanged={() => Promise.resolve()} />);

    await userEvent.click(screen.getByRole('button', { name: 'Activate' }));

    await screen.findByText('The request could not be completed.');
    expect(screen.getByRole('button', { name: 'Activate' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Deactivate' })).not.toBeInTheDocument();
  });

  /** A refused write must not reload the list: there is nothing new to show and it hides the toast. */
  it('does not reload the list when the write did not happen', async () => {
    const onChanged = vi.fn(() => Promise.resolve());
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderWithToasts(<ServicesScreen list={INACTIVE} onChanged={onChanged} />);

    await userEvent.click(screen.getByRole('button', { name: 'Activate' }));

    await screen.findByText('The request could not be completed.');
    expect(onChanged).not.toHaveBeenCalled();
  });
});
