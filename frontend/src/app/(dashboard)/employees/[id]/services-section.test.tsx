import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EMPLOYEE, SERVICE } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { ServicesSection } from './services-section';

/**
 * A refusal whose promise is about **state**, not about a message.
 *
 * The toast is the easy half. The half worth a test is that the ticks stay where the owner put
 * them: this screen holds its selection locally and only adopts the server's answer on success,
 * so a refusal must leave the boxes alone. A screen that snapped back to `employee.serviceIds`
 * would silently discard the owner's work at the moment they most need it kept — and would
 * still look correct, because the toast would be right.
 *
 * It also leaves Save enabled. The button is gated on the selection differing from what the
 * server last confirmed, so if the refusal is retryable the owner must still be able to press it.
 */

const OTHER = { ...SERVICE, id: 'service-2', name: 'Beard trim' };

function section() {
  renderWithToasts(
    <ServicesSection employee={EMPLOYEE} services={[SERVICE, OTHER]} onSaved={vi.fn()} />,
  );
}

/** The employee provides Haircut already; ticking Beard trim is the change being saved. */
async function tickTheOtherAndSave(): Promise<void> {
  await userEvent.click(screen.getByRole('checkbox', { name: /Beard trim/ }));
  await userEvent.click(screen.getByRole('button', { name: 'Save what they provide' }));
}

describe('ServicesSection, refused', () => {
  function serveRefusal() {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'SERVICE_INACTIVE',
        detail: 'Beard trim is not active, so nobody can be assigned to it.',
      },
    });
  }

  it('toasts the server sentence rather than one of its own', async () => {
    serveRefusal();
    section();

    await tickTheOtherAndSave();

    expect(
      await screen.findByText('Beard trim is not active, so nobody can be assigned to it.'),
    ).toBeInTheDocument();
  });

  it('leaves the ticks where the owner put them', async () => {
    serveRefusal();
    section();

    await tickTheOtherAndSave();

    await screen.findByText('Beard trim is not active, so nobody can be assigned to it.');
    expect(screen.getByRole('checkbox', { name: /Beard trim/ })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /Haircut/ })).toBeChecked();
  });

  it('keeps Save pressable, because the change is still unsaved', async () => {
    serveRefusal();
    section();

    await tickTheOtherAndSave();

    await screen.findByText('Beard trim is not active, so nobody can be assigned to it.');
    expect(screen.getByRole('button', { name: 'Save what they provide' })).toBeEnabled();
  });

  /** On success the server's set wins over the submitted one — the control for the case above. */
  it('adopts the set the server answers with when it succeeds', async () => {
    serve({
      kind: 'body',
      bodies: { '/employees': { serviceIds: [SERVICE.id] } },
    });
    section();

    await tickTheOtherAndSave();

    expect(await screen.findByText('What they provide has been saved.')).toBeInTheDocument();
    // The server said Haircut only, so the tick the owner added comes back off.
    expect(screen.getByRole('checkbox', { name: /Beard trim/ })).not.toBeChecked();
  });
});
