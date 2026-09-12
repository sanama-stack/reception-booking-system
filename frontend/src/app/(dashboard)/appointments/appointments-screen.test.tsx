import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { AppointmentsScreen } from './appointments-screen';

/**
 * All three states of the appointment list — docs/09-phase-plan.md §5, rule 7.
 *
 * Two empty states again, and here the distinction is sharper than on the customer list: an owner
 * who has narrowed to one employee and a fortnight, and sees "No appointments yet", has been told
 * their business is empty when it is their filter that is.
 */

const EMPTY_PAGE = {
  timezone: SESSION.business.timezone,
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

function props(over: Partial<Parameters<typeof AppointmentsScreen>[0]> = {}) {
  return {
    path: '/appointments',
    filtered: false,
    onClearFilters: () => {},
    onPage: () => {},
    ...over,
  };
}

describe('AppointmentsScreen', () => {
  it('is busy while the first page is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<AppointmentsScreen {...props()} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: 'The appointment list could not be read.' });
    renderScreen(<AppointmentsScreen {...props()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The appointment list could not be read.',
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('offers a first booking when nothing has ever been booked', async () => {
    serve({ kind: 'body', bodies: { '/appointments': EMPTY_PAGE } });
    renderScreen(<AppointmentsScreen {...props()} />);

    expect(await screen.findByText('No appointments yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Book an appointment' })).toHaveAttribute(
      'href',
      '/appointments/new',
    );
  });

  it('blames the filters, not the business, when filters are set', async () => {
    serve({ kind: 'body', bodies: { '/appointments': EMPTY_PAGE } });
    renderScreen(<AppointmentsScreen {...props({ filtered: true })} />);

    expect(await screen.findByText('Nothing matches those filters')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
    expect(screen.queryByText('No appointments yet')).not.toBeInTheDocument();
  });
});
