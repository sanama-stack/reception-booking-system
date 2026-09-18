import userEvent from '@testing-library/user-event';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderWithToasts, serve } from '@/test/harness';
import { EmployeeForm } from './employee-form';

/**
 * The create form for a person, and the same three-way split the other forms have.
 *
 * Its `rendered` set is derived from the form's own values rather than written out by hand, so
 * unlike `customer-form` it cannot drift out of step with the inputs. What is still worth
 * holding it to is the consequence: a message naming something this form does not hold — an
 * `employeeId`, say, or a server-side field the schema grew later — reaches the banner instead
 * of disappearing.
 */

function createForm() {
  renderWithToasts(<EmployeeForm employee={null} onSaved={vi.fn()} />);
}

async function fillAndSubmit(): Promise<void> {
  await userEvent.type(screen.getByLabelText('Full name'), 'Nino Beridze');
  await userEvent.click(screen.getByRole('button', { name: 'Add employee' }));
}

describe('EmployeeForm, refused', () => {
  it('shows the server sentence when the refusal names no field', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 409,
        code: 'EMPLOYEE_LIMIT_REACHED',
        detail: 'You already have as many people as your plan allows.',
      },
    });
    createForm();

    await fillAndSubmit();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'You already have as many people as your plan allows.',
    );
  });

  it('puts each fielded message against the field it names, and no banner', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [
          { field: 'fullName', message: 'Enter their full name.' },
          { field: 'email', message: 'That address is already in use.' },
        ],
      },
    });
    createForm();

    await fillAndSubmit();

    expect(await screen.findByText('Enter their full name.')).toBeVisible();
    expect(screen.getByLabelText('Email')).toHaveAccessibleDescription(
      'That address is already in use.',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  /** A name this form does not hold has nowhere to land, so it must reach the banner. */
  it('banners a message for a field this form does not hold', async () => {
    serve({
      kind: 'body',
      bodies: {},
      refusing: {
        kind: 'failing',
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'Some of that could not be saved.',
        errors: [{ field: 'serviceIds', message: 'Those services do not all exist.' }],
      },
    });
    createForm();

    await fillAndSubmit();

    expect(await screen.findByRole('alert')).toHaveTextContent('Some of that could not be saved.');
  });

  it('lets the owner try again rather than leaving the button spinning', async () => {
    serve({ kind: 'body', bodies: {}, refusing: { kind: 'failing', status: 500 } });
    createForm();

    await fillAndSubmit();

    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: 'Add employee' })).toBeEnabled();
  });
});
