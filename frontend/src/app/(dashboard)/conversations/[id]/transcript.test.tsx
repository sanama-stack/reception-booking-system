import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { renderScreen, SESSION } from '@/test/harness';
import type { ConversationDetail, ConversationSummary } from '@/lib/conversations';
import { Transcript } from './transcript';

/**
 * The two ways a transcript can be empty, and the sentence that separates them.
 *
 * A conversation nobody spoke in and a conversation whose transcript was purged at ninety days
 * both arrive here as `messages: []`. They are not the same fact, and until the retention purge
 * existed only one of them was possible — so the screen said that one out loud. It now has to ask.
 *
 * **`messageCount` is the trap these tests pin.** The purge does not decrement it, so the purged
 * fixture below carries a count of eight beside an empty transcript. A screen that inferred
 * "nothing was said" from the empty array alone would print that claim directly underneath a Rows
 * fact of 8, and be wrong.
 */

const BASE: ConversationSummary = {
  id: 'c0ffee00-0000-4000-8000-000000000001',
  status: 'CLOSED',
  messageCount: 8,
  promptTokens: 900,
  completionTokens: 300,
  estimatedCostCents: 7,
  customerId: null,
  startedAt: '2026-01-04T09:00:00Z',
  lastMessageAt: '2026-01-04T09:06:00Z',
  messagesPurgedAt: null,
};

function detail(over: Partial<ConversationSummary> = {}): ConversationDetail {
  return { conversation: { ...BASE, ...over }, messages: [] };
}

describe('Transcript, with nothing in it', () => {
  it('says nothing was said when nothing was said', () => {
    renderScreen(
      <Transcript
        detail={{ ...detail(), conversation: { ...BASE, messageCount: 0 } }}
        timezone={SESSION.business.timezone}
      />,
    );

    expect(screen.getByText(/nothing was ever said in it/)).toBeVisible();
    expect(screen.queryByText(/transcript was deleted/)).not.toBeInTheDocument();
  });

  it('says the transcript was purged, and when, once it has been', () => {
    renderScreen(
      <Transcript
        detail={detail({ messagesPurgedAt: '2026-04-06T02:00:00Z' })}
        timezone={SESSION.business.timezone}
      />,
    );

    expect(screen.getByText(/transcript was deleted/)).toBeVisible();

    // The counterfactual. This is the sentence the screen printed before the purge existed, and
    // printing it here would be a lie sitting under a Rows count of eight.
    expect(screen.queryByText(/nothing was ever said in it/)).not.toBeInTheDocument();
  });

  it('keeps the accounting visible after a purge, which is why the row is kept at all', () => {
    renderScreen(
      <Transcript
        detail={detail({ messagesPurgedAt: '2026-04-06T02:00:00Z' })}
        timezone={SESSION.business.timezone}
      />,
    );

    expect(screen.getByText('Rows')).toBeVisible();
    expect(screen.getByText('8')).toBeVisible();
  });

  it('does not name the retention window, which only the server enforces', () => {
    renderScreen(
      <Transcript
        detail={detail({ messagesPurgedAt: '2026-04-06T02:00:00Z' })}
        timezone={SESSION.business.timezone}
      />,
    );

    // A number written down in two languages with nothing checking the copies agree is the
    // coupling this screen deliberately does not create. The date is a fact about this
    // conversation; the window is policy, and the server is the only place it is true.
    expect(screen.queryByText(/90 days/)).not.toBeInTheDocument();
  });
});
