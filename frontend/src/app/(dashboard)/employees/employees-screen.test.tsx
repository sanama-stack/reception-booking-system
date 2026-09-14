import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EMPLOYEE } from '@/test/fixtures';
import { renderWithToasts, serve } from '@/test/harness';
import { EmployeesScreen } from './employees-screen';

/** Rule 7's empty state for the people list. See `services-screen.test.tsx` for the shape. */
describe('EmployeesScreen, with nobody in it', () => {
  it('says why a person is needed and offers the way to add one', () => {
    render(<EmployeesScreen list={{ employees: [] }} onChanged={() => Promise.resolve()} />);

    expect(screen.getByText('Nobody here yet')).toBeInTheDocument();
    expect(screen.getByText(/including yourself, if you provide them/)).toBeVisible();
    expect(screen.getByRole('link', { name: 'Add your first employee' })).toHaveAttribute(
      'href',
      '/employees/new',
    );
  });

  it('draws no table at all rather than an empty one', () => {
    render(<EmployeesScreen list={{ employees: [] }} onChanged={() => Promise.resolve()} />);

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});

/**
 * The same shared toggle as `services-screen.test.tsx`, refused down its **other** path.
 *
 * That file presses Activate, which writes immediately. This one presses Deactivate, which opens
 * a confirmation first — so the refusal has to travel back out through a dialog that is still on
 * screen, and the dialog must not close as though the deactivation had happened. Splitting the
 * two paths across the two screens covers both without either file testing the same thing twice.
 */
describe('EmployeesScreen, when a deactivation is refused', () => {
  const ACTIVE = { employees: [{ ...EMPLOYEE, active: true }] };

  async function deactivate(): Promise<void> {
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
    // The dialog's own button, which carries the same label as the one that opened it.
    const [, confirm] = screen.getAllByRole('button', { name: 'Deactivate' });
    await userEvent.click(confirm!);
  }

  it('toasts the server sentence rather than one of its own', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'EMPLOYEE_HAS_FUTURE_APPOINTMENTS',
        detail: 'Move their upcoming appointments before deactivating them.',
      },
    });
    renderWithToasts(<EmployeesScreen list={ACTIVE} onChanged={() => Promise.resolve()} />);

    await deactivate();

    expect(
      await screen.findByText('Move their upcoming appointments before deactivating them.'),
    ).toBeInTheDocument();
  });

  /**
   * The dialog closing is what a success looks like. If a refusal closed it too, the owner would
   * read the toast as a warning attached to a change that had gone through.
   */
  it('keeps the confirmation open, because nothing was deactivated', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderWithToasts(<EmployeesScreen list={ACTIVE} onChanged={() => Promise.resolve()} />);

    await deactivate();

    await screen.findByText('The request could not be completed.');
    expect(screen.getByText('Deactivate this person?')).toBeVisible();
  });

  it('does not reload the list when the write did not happen', async () => {
    const onChanged = vi.fn(() => Promise.resolve());
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    renderWithToasts(<EmployeesScreen list={ACTIVE} onChanged={onChanged} />);

    await deactivate();

    await screen.findByText('The request could not be completed.');
    expect(onChanged).not.toHaveBeenCalled();
  });
});
