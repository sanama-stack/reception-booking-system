'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Card, cn } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import {
  publicApi,
  type BookedAppointment,
  type ConversationStatus,
  type PublicBusiness,
} from '@/lib/public';
import { Confirmation } from './confirmation';

/**
 * The Receptionist, in the column phase 08 reserved for it.
 *
 * **Every claim on this panel that matters is the server's, not the model's.** The confirmation
 * card is rendered from `ChatReply.appointmentCreated` and never from the prose beside it, so a
 * model that announces a booking it did not make produces a paragraph with no card under it — the
 * failure is visible rather than convincing (docs/05-ai-architecture.md §6). The degradation
 * banners render the server's own `detail` string rather than a sentence written here, for the
 * same reason every other screen does: the server knows which ceiling was hit and this panel does
 * not.
 *
 * **The Classic Flow is never more than one link away.** It is a peer beside this panel rather
 * than something this panel replaces, which is what makes it a genuine fallback for every AI
 * failure mode (docs/05-ai-architecture.md §8) instead of a claim in a document.
 */

/** How many messages must be left before the ceiling is worth mentioning. */
const WARN_BELOW_REMAINING = 8;

/**
 * How long a turn runs before the indicator stops saying "thinking" and admits to working.
 *
 * A turn that books an appointment is a model call, a tool call, and a second model call, and it
 * is routinely several seconds. Saying nothing for that long reads as a hang.
 */
const SLOW_TURN_MS = 6000;

/**
 * One line in the panel.
 *
 * `appointment` is populated **only** from `ChatReply.appointmentCreated`, which the server
 * populates only from a successful `create_appointment`. It is not derived from `text` and there is
 * no code path that could make it so.
 */
interface Bubble {
  key: number;
  author: 'customer' | 'receptionist';
  text: string;
  appointment: BookedAppointment | null;
}

/**
 * What survives a reload, and what does not.
 *
 * The session token alone would resume the conversation on the *server* — the model keeps its
 * window — while leaving the panel blank, so the customer would face an assistant that remembers a
 * conversation they can no longer see. There is no public endpoint that returns a transcript (and
 * there should not be: it would read somebody's conversation from a token in a URL), so the
 * bubbles are kept here beside the token.
 *
 * `sessionStorage`, not `localStorage`, and that is the whole of the tab-scoping the phase asks
 * for: a reload resumes and a new tab starts fresh, because that is what `sessionStorage` already
 * means. It also means a transcript containing a customer's name and number is gone when the tab
 * closes, rather than persisting on a shared machine.
 */
interface StoredConversation {
  token: string;
  conversationId: string;
  status: ConversationStatus;
  messagesRemaining: number;
  bubbles: Bubble[];
}

/**
 * Per slug, so two businesses open in one tab do not resume each other's conversation.
 *
 * Versioned, so a shape change in a future phase is ignored rather than misread — a stale entry
 * that no longer parses is dropped and the customer starts a new conversation, which is the
 * failure this key can afford.
 */
function storageKey(slug: string): string {
  return `reception.chat.v1.${slug}`;
}

/**
 * What went wrong, and whether the customer can do anything about it.
 *
 * `message` is always the server's own words. The three flags are the only thing decided here, and
 * each is decided from the error **code** rather than from the sentence — the codes are the
 * contract and the prose is not (docs/04-api-overview.md §3).
 */
interface Degradation {
  message: string;
  /** The composer goes away: nothing more can be said to this conversation. */
  terminal: boolean;
  /** Offer a fresh conversation. Only where a fresh one could plausibly behave differently. */
  restartable: boolean;
}

function degrade(error: ApiError): Degradation {
  switch (error.code) {
    /**
     * A ceiling, not a fault. Two of them arrive under this one code — the conversation's
     * forty-message limit and the business's daily cost cap — and they are **deliberately not told
     * apart here**. The server's own sentence distinguishes them; branching on that sentence would
     * be parsing prose to decide behaviour, which is the exact thing `appointmentCreated` exists to
     * avoid doing.
     *
     * Restartable anyway, and the ceiling case is why: forty messages is the common one, a new
     * conversation genuinely fixes it, and the server's message for it ends "or start a new chat" —
     * an instruction with no button beside it would be worse than a button that occasionally leads
     * to the same honest refusal a second time.
     */
    case 'AI_LIMIT_REACHED':
      return { message: error.message, terminal: true, restartable: true };

    /**
     * Transient by construction. `ConversationService` records the turn and leaves the conversation
     * `ACTIVE` before throwing this, precisely so a customer who retries after an outage carries on
     * where they were — so the composer stays and the banner is a note rather than an ending.
     */
    case 'AI_UNAVAILABLE':
      return { message: error.message, terminal: false, restartable: false };

    /**
     * The token names no conversation the server still holds. Nothing can be said to it, and
     * everything can be said to a new one — so the stored session is dropped by the caller and the
     * only offer is to begin again.
     */
    case 'NOT_FOUND':
      return { message: error.message, terminal: true, restartable: true };

    /** Too fast, not too much. Waiting is the fix and the composer is what they wait with. */
    case 'RATE_LIMITED':
      return { message: error.message, terminal: false, restartable: false };

    default:
      return { message: error.message, terminal: false, restartable: false };
  }
}

export function ReceptionistPanel({ slug, business }: { slug: string; business: PublicBusiness }) {
  const [bubbles, setBubbles] = useState<Bubble[]>([]);
  const [status, setStatus] = useState<ConversationStatus>('ACTIVE');
  const [messagesRemaining, setMessagesRemaining] = useState<number | null>(null);
  const [draft, setDraft] = useState('');
  const [pending, setPending] = useState(false);
  const [degradation, setDegradation] = useState<Degradation | null>(null);

  /**
   * The session token, held in a ref rather than in state.
   *
   * Nothing renders it and nothing should re-render because it changed — but `send` must read the
   * value the *last* call wrote, not the one captured when it was defined. A state variable would
   * give a stale token to a second message sent before the first re-render landed.
   */
  const token = useRef<string | null>(null);
  const conversationId = useRef<string | null>(null);

  /**
   * Whether `sessionStorage` has been read yet.
   *
   * The first client render must match the server's HTML, so restoring during render would be a
   * hydration mismatch. It happens in an effect instead — and nothing is written back until it has,
   * or the empty initial state would overwrite the conversation being restored.
   */
  const [restored, setRestored] = useState(false);

  const nextKey = useRef(0);
  const scroller = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setRestored(true);
    let raw: string | null = null;
    try {
      raw = window.sessionStorage.getItem(storageKey(slug));
    } catch {
      // Storage can be denied outright — Safari's private mode, or a browser configured to block
      // it. A conversation that cannot be resumed is worth strictly less than one that cannot be
      // had, so this is swallowed and the panel runs without persistence.
      return;
    }
    if (!raw) return;

    try {
      const stored = JSON.parse(raw) as StoredConversation;
      token.current = stored.token;
      conversationId.current = stored.conversationId;
      setBubbles(stored.bubbles);
      setStatus(stored.status);
      setMessagesRemaining(stored.messagesRemaining);
      nextKey.current = stored.bubbles.length;
    } catch {
      // A stale or hand-edited entry. Dropped rather than repaired.
      window.sessionStorage.removeItem(storageKey(slug));
    }
  }, [slug]);

  useEffect(() => {
    if (!restored) return;
    if (!token.current || !conversationId.current) return;
    const stored: StoredConversation = {
      token: token.current,
      conversationId: conversationId.current,
      status,
      messagesRemaining: messagesRemaining ?? 0,
      bubbles,
    };
    try {
      window.sessionStorage.setItem(storageKey(slug), JSON.stringify(stored));
    } catch {
      // Storage denied, or full. See above: not a reason to break the conversation in progress.
    }
  }, [restored, slug, bubbles, status, messagesRemaining]);

  /** The newest line, kept in view — the panel scrolls inside itself rather than the page. */
  useEffect(() => {
    const element = scroller.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [bubbles, pending, degradation]);

  const forget = useCallback(() => {
    token.current = null;
    conversationId.current = null;
    try {
      window.sessionStorage.removeItem(storageKey(slug));
    } catch {
      // Nothing to clean up if it was never written.
    }
  }, [slug]);

  function append(bubble: Omit<Bubble, 'key'>) {
    setBubbles((current) => [...current, { ...bubble, key: nextKey.current++ }]);
  }

  function restart() {
    forget();
    setBubbles([]);
    setStatus('ACTIVE');
    setMessagesRemaining(null);
    setDegradation(null);
    nextKey.current = 0;
  }

  async function send(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const message = draft.trim();
    if (!message || pending) return;

    setDraft('');
    setDegradation(null);
    append({ author: 'customer', text: message, appointment: null });
    setPending(true);

    try {
      /**
       * The conversation is opened by the first message rather than by the page.
       *
       * Opening on mount would write an `ai_conversations` row for every visitor who scrolled past
       * the panel, and the owner's transcript screen would fill with conversations nobody had. A
       * conversation exists here because somebody spoke.
       */
      if (!token.current) {
        const started = await publicApi.startChat(slug);
        token.current = started.sessionToken;
        conversationId.current = started.conversationId;
      }

      const answer = await publicApi.chat(slug, {
        sessionToken: token.current,
        message,
      });

      append({
        author: 'receptionist',
        text: answer.reply,
        // From the field, never from `answer.reply`. This is the hallucination control.
        appointment: answer.appointmentCreated,
      });
      setStatus(answer.conversationStatus);
      setMessagesRemaining(answer.messagesRemaining);
    } catch (cause) {
      const error =
        cause instanceof ApiError
          ? cause
          : new ApiError({
              code: 'INTERNAL_ERROR',
              message: 'The receptionist could not be reached. You can book directly on this page.',
              status: 0,
            });

      const outcome = degrade(error);
      setDegradation(outcome);
      // A dead token is worth dropping immediately: keeping it would make every later message fail
      // the same way, including the one sent after "start a new chat".
      if (error.code === 'NOT_FOUND' || (outcome.terminal && outcome.restartable)) forget();
      if (outcome.terminal) setStatus('CLOSED');
    } finally {
      setPending(false);
    }
  }

  const closed = status !== 'ACTIVE' || (degradation?.terminal ?? false);
  const empty = bubbles.length === 0;

  return (
    <Card className="border-brand/25 bg-brand/[0.03] flex flex-col gap-3 p-4">
      <header>
        <h2 className="text-ink text-sm font-semibold">Ask the receptionist</h2>
        <p className="text-ink-muted mt-1 text-sm leading-relaxed">
          {/* One line each. A template literal wrapped across two source lines carries its own
              indentation into the string — HTML collapses it, so it looks fine and is still a
              sentence with fifteen spaces in the middle of it. */}
          {empty
            ? `Tell it what you need and it will book it — or ask ${business.name} anything about what they offer.`
            : `You are talking to ${business.name}'s booking assistant.`}
        </p>
      </header>

      {!empty && (
        <div
          ref={scroller}
          // A bounded, independently scrolling transcript. Without the cap the panel grows without
          // limit and pushes the opening hours below it off any screen; `overscroll-contain` is
          // what stops a flick inside it scrolling the page behind it once it reaches the end.
          className="flex max-h-[26rem] flex-col gap-3 overflow-y-auto overscroll-contain pr-1"
          role="log"
          aria-label="Conversation"
          aria-live="polite"
        >
          {bubbles.map((bubble) => (
            <Line key={bubble.key} bubble={bubble} business={business} />
          ))}
          {pending && <Working />}
        </div>
      )}

      {degradation && (
        <div
          role="alert"
          className="border-border bg-surface text-ink rounded-md border p-3 text-sm leading-relaxed"
        >
          {/* The server's sentence, verbatim. It knows which ceiling was hit; this panel does not. */}
          <p>{degradation.message}</p>
          {degradation.restartable && (
            <Button variant="secondary" className="mt-3" onClick={restart}>
              Start a new chat
            </Button>
          )}
        </div>
      )}

      {closed ? (
        <p className="text-ink-muted text-sm leading-relaxed">
          {/* The composer is gone, so the remaining door is named rather than implied. */}
          The form on this page can do everything the receptionist could.
        </p>
      ) : (
        <form onSubmit={send} className="flex flex-col gap-2">
          <label htmlFor="receptionist-message" className="sr-only">
            Message the receptionist
          </label>
          <textarea
            id="receptionist-message"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              // Enter sends and Shift+Enter breaks the line, which is what a chat composer means
              // everywhere. `isComposing` is what keeps that from firing mid-word for anyone
              // typing through an IME, where Enter commits a candidate rather than a message.
              if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                event.preventDefault();
                event.currentTarget.form?.requestSubmit();
              }
            }}
            rows={2}
            // 2,000 is the server's own cap on a message. Enforced here as well so the refusal is
            // the textarea declining a keystroke rather than a round trip that fails validation.
            maxLength={2000}
            disabled={pending}
            placeholder={`Book me a haircut on Thursday afternoon`}
            className={cn(
              'border-border bg-surface text-ink resize-none rounded-md border px-3 py-2 text-sm',
              'placeholder:text-ink-muted disabled:opacity-50',
            )}
          />
          <div className="flex items-center justify-between gap-3">
            <Button type="submit" loading={pending} disabled={!draft.trim()}>
              Send
            </Button>
            {messagesRemaining !== null && messagesRemaining <= WARN_BELOW_REMAINING && (
              <p className="text-ink-muted text-xs">
                {/* The server's count, not a tally of the bubbles above: the ceiling counts tool
                    rows this panel never sees, so counting here would be wrong, and wrong in the
                    direction that promises more than there is. */}
                {messagesRemaining === 0
                  ? 'No messages left in this chat'
                  : `${messagesRemaining} ${messagesRemaining === 1 ? 'message' : 'messages'} left in this chat`}
              </p>
            )}
          </div>
        </form>
      )}

      {/*
        The permanent affordance. Present in every state — before the first message, mid-turn, and
        after a ceiling — because the phase's requirement is that the AI is the default door and not
        the only one. It is an anchor rather than a navigation: the Classic Flow is already on this
        page, in the column beside this one.
      */}
      <a
        href="#classic-flow"
        className="text-ink-muted hover:text-ink text-xs underline underline-offset-2"
      >
        Or book the classic way
      </a>
    </Card>
  );
}

/** One turn, and the confirmation card where the server said a booking happened. */
function Line({ bubble, business }: { bubble: Bubble; business: PublicBusiness }) {
  if (bubble.author === 'customer') {
    return (
      <p className="bg-brand text-brand-contrast ml-6 self-end rounded-lg rounded-br-sm px-3 py-2 text-sm leading-relaxed whitespace-pre-line">
        {bubble.text}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {bubble.text && (
        <p className="bg-surface text-ink border-border mr-6 self-start rounded-lg rounded-bl-sm border px-3 py-2 text-sm leading-relaxed whitespace-pre-line">
          {bubble.text}
        </p>
      )}
      {bubble.appointment && (
        /*
          The Classic Flow's own confirmation, not a second card that resembles it. A booking is
          the same event whichever door it came in through, and `appointmentCreated` is
          `BookedAppointment` on the wire precisely so one component renders either.

          `email` is omitted rather than passed as `null`: this panel does not know whether an
          address was given, because the customer gave it to the model rather than to a form here.
          `null` would have meant "they left it blank", which is a claim this panel is in no
          position to make — see `Confirmation`'s own note.

          No `onBookAnother`: booking another is done by saying so, and the composer is right below.
        */
        <Confirmation appointment={bubble.appointment} business={business} />
      )}
    </div>
  );
}

/**
 * That something is happening, and roughly how long it has been happening for.
 *
 * **It does not name a tool, and cannot.** `ChatReply` carries a reply, a status, a count and an
 * appointment — there is no tool activity on the wire, because a turn is one non-streaming `POST`
 * and streaming is explicitly out of scope for this phase. Writing "checking availability…" here
 * would be inventing a fact about a request whose contents this panel has never seen, which is the
 * same mistake as reading a booking out of prose. What it can honestly do is stop pretending a
 * six-second turn is instant. The tool calls themselves are visible, with their arguments and
 * their results, on the owner's transcript screen.
 */
function Working() {
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => setSlow(true), SLOW_TURN_MS);
    return () => window.clearTimeout(timer);
  }, []);

  return (
    <p className="text-ink-muted mr-6 flex items-center gap-2 self-start text-sm">
      <span aria-hidden className="flex gap-1">
        {[0, 1, 2].map((dot) => (
          <span
            key={dot}
            className="bg-ink-muted/60 inline-block h-1.5 w-1.5 animate-pulse rounded-full"
            style={{ animationDelay: `${dot * 150}ms` }}
          />
        ))}
      </span>
      {slow ? 'Still working — booking takes a moment' : 'Thinking'}
    </p>
  );
}
