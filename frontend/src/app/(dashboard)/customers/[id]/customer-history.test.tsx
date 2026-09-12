import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { CustomerHistory } from './customer-history';

/** All three states of one customer's history — docs/09-phase-plan.md §5, rule 7. */

const EMPTY_PAGE = {
  timezone: SESSION.business.timezone,
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

describe('CustomerHistory', () => {
  it('is busy while the history is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<CustomerHistory path="/appointments?customerId=customer-1" onPage={() => {}} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: 'The history could not be read.' });
    renderScreen(<CustomerHistory path="/appointments?customerId=customer-1" onPage={() => {}} />);

    expect(await screen.findByRole('alert')).toHaveTextContent('The history could not be read.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('explains a customer with no appointments rather than drawing an empty table', async () => {
    serve({ kind: 'body', bodies: { '/appointments': EMPTY_PAGE } });
    renderScreen(<CustomerHistory path="/appointments?customerId=customer-1" onPage={() => {}} />);

    expect(await screen.findByText('Nothing booked')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
