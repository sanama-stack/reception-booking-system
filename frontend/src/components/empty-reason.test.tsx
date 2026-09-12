import { describe, expect, it } from 'vitest';
import type { EmptyReason } from '@/lib/scheduling';
import { explainEmptyReason, type EmptyReasonContext } from './empty-reason';

/**
 * The empty-state copy of the three screens that render `reason.title` — the availability preview,
 * the booking flow and the public page — which is why it is asserted here rather than three times
 * over. Those screens pass the explanation straight to `EmptyState`.
 *
 * This is the sharpest empty state in the application: an empty slot list tells nobody anything,
 * and the four reasons mean four different things to two different audiences.
 */

const REASONS: EmptyReason[] = [
  'NO_ELIGIBLE_EMPLOYEE',
  'OUTSIDE_HORIZON',
  'CLOSED',
  'FULLY_BOOKED',
];

function context(over: Partial<EmptyReasonContext> = {}): EmptyReasonContext {
  return {
    audience: 'owner',
    serviceName: 'Cut and finish',
    durationMinutes: 45,
    employeeName: null,
    ...over,
  };
}

describe('explainEmptyReason', () => {
  it('gives every reason its own words, for both audiences', () => {
    for (const audience of ['owner', 'customer'] as const) {
      const titles = REASONS.map(
        (reason) => explainEmptyReason(reason, context({ audience })).title,
      );

      // Four reasons, four distinct sentences. A duplicate here would mean two situations an
      // owner must act on differently are being reported identically.
      expect(new Set(titles).size, `${audience} titles: ${titles.join(' | ')}`).toBe(
        REASONS.length,
      );
      for (const title of titles) expect(title).not.toBe('');
    }
  });

  /**
   * The audience changes more than the tone. Every owner-facing `action` is a link into a setting;
   * a Customer cannot open any of those doors, so they must never be offered one.
   */
  it('offers a setting to the owner and never to the customer', () => {
    const ownerActions = REASONS.map(
      (reason) => explainEmptyReason(reason, context()).action,
    ).filter(Boolean);
    expect(ownerActions.length).toBeGreaterThan(0);

    for (const reason of REASONS) {
      const explanation = explainEmptyReason(reason, context({ audience: 'customer' }));
      expect(explanation.action, `${reason} offered a customer a link`).toBeUndefined();
    }
  });

  it('advises a customer on both bounds of the booking window, not only the far one', () => {
    const explanation = explainEmptyReason('OUTSIDE_HORIZON', context({ audience: 'customer' }));

    // One reason covers both bounds and nothing in the response distinguishes them, so advice
    // that names only "further out" is wrong half the time.
    expect(explanation.description).toMatch(/nearer to today/);
    expect(explanation.description).toMatch(/further out/);
  });

  it('names the person when one was asked about, and does not invent one when none was', () => {
    const named = explainEmptyReason(
      'NO_ELIGIBLE_EMPLOYEE',
      context({ audience: 'customer', employeeName: 'Nino' }),
    );
    expect(named.title).toContain('Nino');

    const anyone = explainEmptyReason('NO_ELIGIBLE_EMPLOYEE', context({ audience: 'customer' }));
    expect(anyone.title).not.toContain('Nino');
    expect(anyone.description).toContain('Cut and finish');
  });

  it('says the duration in words rather than in minutes', () => {
    expect(explainEmptyReason('CLOSED', context({ durationMinutes: 90 })).description).toContain(
      '1 hr 30 min',
    );
    expect(explainEmptyReason('CLOSED', context({ durationMinutes: 45 })).description).toContain(
      '45 min',
    );
  });

  /**
   * The contract says a reason accompanies every empty result. If one is ever missing, the screen
   * says so plainly rather than picking the most likely explanation and presenting it as fact.
   */
  it('admits it was given no reason rather than guessing one', () => {
    for (const audience of ['owner', 'customer'] as const) {
      const explanation = explainEmptyReason(null, context({ audience }));
      expect(explanation.title).toBe('No times available');
      expect(explanation.description).not.toBe('');
    }

    expect(explainEmptyReason(null, context()).description).toMatch(/no reason/);
  });
});
