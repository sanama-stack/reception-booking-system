import { describe, expect, it } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { CustomersScreen } from './customers-screen';

/**
 * The three states of one screen, driven through the real API client against a stubbed transport
 * — docs/09-phase-plan.md §5, rule 7.
 *
 * This screen has *two* empty states and the difference between them is the whole point of having
 * them: "nobody has ever booked" and "nobody matches that search" are different situations with
 * different next steps, and a screen that showed the first to someone mid-search would be telling
 * them they have no customers.
 */

const EMPTY_PAGE = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

function props(over: Partial<Parameters<typeof CustomersScreen>[0]> = {}) {
  return {
    path: '/customers',
    searching: false,
    timezone: SESSION.business.timezone,
    onClearSearch: () => {},
    onPage: () => {},
    ...over,
  };
}

describe('CustomersScreen', () => {
  it('is busy while the first page is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<CustomersScreen {...props()} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: 'The customer list could not be read.' });
    renderScreen(<CustomersScreen {...props()} />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The customer list could not be read.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('explains an empty list rather than drawing an empty table', async () => {
    serve({ kind: 'body', bodies: { '/customers': EMPTY_PAGE } });
    renderScreen(<CustomersScreen {...props()} />);

    expect(await screen.findByText('No customers yet')).toBeInTheDocument();
    // The detail that makes it an explanation: there is no "add customer" here, by design.
    expect(screen.getByText(/created by their first booking/)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('says something different, and offers a way out, when a search matched nothing', async () => {
    serve({ kind: 'body', bodies: { '/customers': EMPTY_PAGE } });
    renderScreen(<CustomersScreen {...props({ searching: true, path: '/customers?q=zzz' })} />);

    expect(await screen.findByText('Nobody matches that')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear search' })).toBeInTheDocument();
    expect(screen.queryByText('No customers yet')).not.toBeInTheDocument();
  });

  it('renders the rows once there are any', async () => {
    serve({
      kind: 'body',
      bodies: {
        '/customers': {
          ...EMPTY_PAGE,
          totalElements: 1,
          totalPages: 1,
          content: [
            {
              id: 'customer-1',
              fullName: 'Clara Classic',
              phone: '+995555123456',
              email: 'clara@example.com',
              appointmentCount: 2,
              lastAppointmentAt: '2026-03-10T09:00:00Z',
              createdAt: '2026-01-05T08:00:00Z',
            },
          ],
        },
      },
    });
    renderScreen(<CustomersScreen {...props()} />);

    await waitFor(() => expect(screen.getByRole('table')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Clara Classic' })).toBeInTheDocument();
    expect(screen.queryByText('No customers yet')).not.toBeInTheDocument();
  });
});
