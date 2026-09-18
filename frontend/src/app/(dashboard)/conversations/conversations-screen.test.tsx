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
    unofferedOnly: false,
    onUnofferedOnly: () => {},
    ...over,
  };
}

/** A row with everything the screen reads, so a test overrides only what it is about. */
function conversation(over: Record<string, unknown> = {}) {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    status: 'CLOSED',
    messageCount: 8,
    promptTokens: 900,
    completionTokens: 120,
    estimatedCostCents: 3,
    customerId: null,
    startedAt: '2026-09-13T09:00:00Z',
    lastMessageAt: '2026-09-13T09:04:00Z',
    messagesPurgedAt: null,
    writes: 0,
    unofferedWrites: 0,
    ...over,
  };
}

function pageOf(...items: ReturnType<typeof conversation>[]) {
  return { items, page: 0, size: 20, total: items.length };
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

  /**
   * The marker an owner cannot get any other way — ADR-0012.
   *
   * A conversation that booked somebody onto a time it never offered is indistinguishable from an
   * ordinary one in every other column: the appointment is real, the slot was bookable, the status
   * is `CLOSED` and nothing failed. So the first two tests below are a pair, and the second is the
   * one that matters: the same row with the same counts except the one that says it went wrong.
   */
  it('says nothing about a conversation that wrote nothing', async () => {
    serve({ kind: 'body', bodies: { '/conversations': pageOf(conversation()) } });
    renderScreen(<ConversationsScreen {...props()} />);

    expect(await screen.findByRole('table')).toBeInTheDocument();
    // The badge's own shape, not a bare /unoffered/ — the filter button is also on screen and its
    // label contains the word.
    expect(screen.queryByText(/of \d+ unoffered/)).not.toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('counts a write that landed on a time the conversation never offered', async () => {
    serve({
      kind: 'body',
      bodies: { '/conversations': pageOf(conversation({ writes: 2, unofferedWrites: 1 })) },
    });
    renderScreen(<ConversationsScreen {...props()} />);

    expect(await screen.findByText('1 of 2 unoffered')).toBeInTheDocument();
  });

  it('reports an ordinary booking without calling it out', async () => {
    serve({
      kind: 'body',
      bodies: { '/conversations': pageOf(conversation({ writes: 1, unofferedWrites: 0 })) },
    });
    renderScreen(<ConversationsScreen {...props()} />);

    expect(await screen.findByText('1 booking')).toBeInTheDocument();
    expect(screen.queryByText(/of \d+ unoffered/)).not.toBeInTheDocument();
  });

  /**
   * "No conversations at all" and "none unoffered" are different answers, and the filtered empty
   * state has to keep the control that produced it — an empty state with no way back is a dead end.
   */
  it('distinguishes an empty filter from an empty list, and keeps the way back', async () => {
    serve({ kind: 'body', bodies: { '/conversations': EMPTY_PAGE } });
    renderScreen(<ConversationsScreen {...props({ unofferedOnly: true })} />);

    expect(await screen.findByText('Nothing unoffered')).toBeInTheDocument();
    expect(screen.queryByText('No conversations yet')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /showing unoffered only/i })).toBeInTheDocument();
  });
});
