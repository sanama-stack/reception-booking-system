import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
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
