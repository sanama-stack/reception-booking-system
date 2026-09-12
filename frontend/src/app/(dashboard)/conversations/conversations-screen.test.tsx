import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderScreen, serve, SESSION } from '@/test/harness';
import { ConversationsScreen } from './conversations-screen';

/**
 * All three states of the conversation list — docs/09-phase-plan.md §5, rule 7.
 *
 * One empty state, and the sentence matters more than most: a receptionist that has never been
 * spoken to and one that is broken look identical from this screen, so the copy says which is
 * being reported.
 */

const EMPTY_PAGE = { items: [], page: 0, size: 20, total: 0 };

function props(over: Partial<Parameters<typeof ConversationsScreen>[0]> = {}) {
  return {
    path: '/conversations',
    timezone: SESSION.business.timezone,
    onPage: () => {},
    ...over,
  };
}

describe('ConversationsScreen', () => {
  it('is busy while the first page is in flight', () => {
    serve({ kind: 'pending' });
    renderScreen(<ConversationsScreen {...props()} />);

    expect(screen.getByRole('status', { name: 'Loading' })).toBeInTheDocument();
  });

  it("shows the server's message when the read fails, with a way back", async () => {
    serve({ kind: 'failing', detail: 'The conversations could not be read.' });
    renderScreen(<ConversationsScreen {...props()} />);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The conversations could not be read.',
    );
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('says a conversation is recorded only when somebody speaks', async () => {
    serve({ kind: 'body', bodies: { '/conversations': EMPTY_PAGE } });
    renderScreen(<ConversationsScreen {...props()} />);

    expect(await screen.findByText('No conversations yet')).toBeInTheDocument();
    expect(
      screen.getByText(/Nothing is recorded for a visitor who only reads the page/),
    ).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
