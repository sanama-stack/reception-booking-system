import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { BookedAppointment, PublicBusiness } from '@/lib/public';
import { serve } from '@/test/harness';
import { ReceptionistPanel } from './receptionist-panel';

/**
 * That a card on this panel comes from the server, and from which field.
 *
 * **The reply text is deliberately a lie in every case below.** The model says one thing and the
 * card is rendered from another, which is the whole of docs/05-ai-architecture.md §6: if a card
 * could be produced by prose, none of these tests would be able to tell the difference. So the
 * reply always claims an outcome the tool result does not support, and the assertions check that
 * the panel believed the field.
 *
 * A move had no card at all until `appointmentUpdated` existed — the server landed on a date,
 * `reschedule_appointment` returned it, and nothing carried it here — so the model's sentence was
 * the only account of the new time a customer could read. That is the surface issue #17 measures.
 */

const BUSINESS: PublicBusiness = {
  name: 'Aria Studio',
  description: null,
  addressLine: null,
  city: null,
  country: null,
  phone: null,
  email: null,
  website: null,
  timezone: 'Asia/Tbilisi',
  currency: 'GEL',
  cancellationWindowHours: 24,
  cancellationPolicy: null,
  receptionistAvailable: true,
  hours: [],
};

const MOVED: BookedAppointment = {
  id: '11111111-1111-1111-1111-111111111111',
  confirmationCode: '7QK4M2XR',
  startsAt: '2026-09-21T12:00:00+04:00',
  endsAt: '2026-09-21T13:00:00+04:00',
  timezone: 'Asia/Tbilisi',
  service: { name: 'Haircut', durationMinutes: 60 },
  employee: { fullName: 'Nino Beridze' },
  price: { amount: '60.00', currency: 'GEL' },
  confirmationSent: true,
};

const BOOKED: BookedAppointment = { ...MOVED, id: '22222222-2222-2222-2222-222222222222' };

function panelAnswering(
  reply: Partial<Record<'appointmentCreated' | 'appointmentUpdated', BookedAppointment | null>> & {
    reply: string;
  },
) {
  serve({
    kind: 'body',
    bodies: {
      // The longer path wins the prefix match, so the session call and the turn call are
      // answered separately — see `harness.tsx` on why longest-prefix is the rule.
      '/public/businesses/aria/chat/session': { conversationId: 'c-1', sessionToken: 't-1' },
      '/public/businesses/aria/chat': {
        conversationStatus: 'ACTIVE',
        messagesRemaining: 30,
        appointmentCreated: null,
        appointmentUpdated: null,
        ...reply,
      },
    },
  });
  render(<ReceptionistPanel slug="aria" business={BUSINESS} />);
}

async function say(message: string) {
  await userEvent.type(screen.getByRole('textbox'), message);
  await userEvent.click(screen.getByRole('button', { name: /send/i }));
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.sessionStorage.clear();
});

describe('the receptionist panel', () => {
  it('renders a card for a move, from appointmentUpdated', async () => {
    panelAnswering({ reply: 'All done, see you Tuesday.', appointmentUpdated: MOVED });

    await say('move it to twelve');

    // The reply said Tuesday. The card says what the server actually wrote.
    expect(await screen.findByText('Moved')).toBeInTheDocument();
    expect(screen.getByText(/haircut with nino beridze/i)).toBeInTheDocument();
    expect(screen.getByText(/12:00/)).toBeInTheDocument();
    expect(screen.getByText('All done, see you Tuesday.')).toBeInTheDocument();
  });

  it('renders no card when the model claims a move the server did not make', async () => {
    panelAnswering({ reply: "You're all moved to Tuesday." });

    await say('move it to twelve');

    expect(await screen.findByText("You're all moved to Tuesday.")).toBeInTheDocument();
    // The lie is a paragraph with nothing under it — visible absence, not a convincing card.
    await waitFor(() => expect(screen.queryByText('Moved')).not.toBeInTheDocument());
    expect(screen.queryByText('Booked')).not.toBeInTheDocument();
  });

  it('tells a move and a booking apart in the same turn', async () => {
    panelAnswering({
      reply: 'Done and done.',
      appointmentUpdated: MOVED,
      appointmentCreated: BOOKED,
    });

    await say('move my tuesday one and book me a friday too');

    // Two cards, each named for its own event. One field standing in for both would lose one.
    expect(await screen.findByText('Moved')).toBeInTheDocument();
    expect(screen.getByText('Booked')).toBeInTheDocument();
  });
});

/**
 * The refusal that has nowhere else to go: there is no form here, only a transcript.
 *
 * `degrade()` maps four server codes onto two independent bits — whether the conversation is
 * over, and whether starting a new one would help — and the pairing is not guessable from the
 * code's name. `AI_UNAVAILABLE` is transient and keeps the composer; `AI_LIMIT_REACHED` ends the
 * conversation but offers a restart; `RATE_LIMITED` ends nothing. Getting a pair wrong either
 * strands a customer who could have carried on, or offers a button that leads straight back to
 * the same refusal.
 *
 * The sentence is the server's, verbatim, in every case. Two codes arrive under
 * `AI_LIMIT_REACHED` — the message ceiling and the daily cost cap — and the panel deliberately
 * does not tell them apart, because distinguishing them means parsing prose to decide behaviour,
 * which is the thing this whole panel is built to avoid.
 */
function panelRefusing(code: string, detail: string) {
  serve({
    kind: 'body',
    bodies: {
      // Longer prefix, so the session still succeeds: the panel has to get as far as a turn
      // before there is anything to refuse.
      '/public/businesses/aria/chat/session': { conversationId: 'c-1', sessionToken: 't-1' },
    },
    refusing: { kind: 'failing', path: '/public/businesses/aria/chat', status: 503, code, detail },
  });
  render(<ReceptionistPanel slug="aria" business={BUSINESS} />);
}

describe('the receptionist panel, when a turn is refused', () => {
  it('puts the server sentence in the transcript, verbatim', async () => {
    panelRefusing('AI_UNAVAILABLE', 'The assistant is briefly unavailable. Please try again.');

    await say('are you open on Sunday?');

    expect(
      await screen.findByText('The assistant is briefly unavailable. Please try again.'),
    ).toBeVisible();
  });

  /** Transient by construction: the turn was recorded and the conversation left ACTIVE. */
  it('keeps the composer when the failure is transient', async () => {
    panelRefusing('AI_UNAVAILABLE', 'The assistant is briefly unavailable. Please try again.');

    await say('are you open on Sunday?');

    await screen.findByText('The assistant is briefly unavailable. Please try again.');
    expect(screen.getByRole('textbox')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /start a new/i })).not.toBeInTheDocument();
  });

  /** A ceiling, not a fault — and the one case where beginning again genuinely fixes it. */
  it('ends the conversation and offers a restart when a ceiling is reached', async () => {
    panelRefusing(
      'AI_LIMIT_REACHED',
      'This conversation has reached its limit. Book above, or start a new chat.',
    );

    await say('and one more thing');

    expect(
      await screen.findByText(
        'This conversation has reached its limit. Book above, or start a new chat.',
      ),
    ).toBeVisible();
    expect(await screen.findByRole('button', { name: /start a new/i })).toBeVisible();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  /** Too fast, not too much. Waiting is the fix, and the composer is what they wait with. */
  it('keeps the composer when the customer is only going too fast', async () => {
    panelRefusing('RATE_LIMITED', 'One moment — you are sending messages very quickly.');

    await say('hello hello hello');

    await screen.findByText('One moment — you are sending messages very quickly.');
    expect(screen.getByRole('textbox')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /start a new/i })).not.toBeInTheDocument();
  });
});
