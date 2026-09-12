import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
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
