/**
 * The wire shapes of `/conversations/*`, mirroring `ConversationResponses` on the server.
 *
 * This is the owner's window into what the Receptionist has been saying — both a product feature
 * and the primary debugging tool when it behaves oddly, which is why a transcript carries tool
 * calls with their arguments and their results rather than only the prose. "It told my customer
 * the wrong price" is answerable from this screen and from nowhere else.
 *
 * **Behind authentication, `OWNER` and `ADMIN` only.** Nothing here is reachable from the public
 * surface, and nothing on the public surface returns a transcript.
 */

import type { ConversationStatus } from '@/lib/public';
import type { IsoInstant } from '@/lib/time';

/**
 * The status is imported rather than redeclared.
 *
 * One enum on the server — `ai.application.ConversationStatus` — surfaced on two doors: the chat
 * panel reads it off a reply, and this screen reads it off a row. A second copy of the same three
 * strings could only ever agree redundantly or drift, and drift here means a dashboard that files
 * a conversation under a state the panel would not recognise.
 */
export type { ConversationStatus };

export interface ConversationSummary {
  id: string;
  status: ConversationStatus;
  /**
   * User, assistant **and tool** rows alike — the same total the forty-message ceiling counts.
   * It is therefore always larger than the number of bubbles the customer saw, and a screen that
   * called it "messages" without qualification would invite an owner to conclude their customer
   * typed twenty times.
   */
  messageCount: number;
  promptTokens: number;
  completionTokens: number;
  /**
   * What this conversation is estimated to have cost, in cents, accumulated by `CostTracker`.
   *
   * The same figure the daily cap is checked against, which is what makes it worth showing: an
   * owner who hits `AI_LIMIT_REACHED` can see which conversations spent the budget.
   */
  estimatedCostCents: number;
  /** Null until a tool identified the caller — most conversations never book and stay anonymous. */
  customerId: string | null;
  startedAt: IsoInstant;
  /** Never null: a conversation's first `last_message_at` is the instant it was opened. */
  lastMessageAt: IsoInstant;
  /**
   * When the retention purge deleted this conversation's transcript, or null while it still has
   * one. Ninety days after a conversation's last activity (`ConversationLimits`).
   *
   * **The screen cannot tell the truth without it.** `messageCount` is not decremented by the
   * purge, so a purged conversation arrives here as a count of eight with no messages — which is
   * indistinguishable, from the client's side, from a customer who opened the chat panel and
   * closed the tab. Those are the two states the transcript's empty case has to separate, and one
   * of them is not an empty transcript at all.
   */
  messagesPurgedAt: IsoInstant | null;
}

/**
 * A page of conversations.
 *
 * **Not Spring's `Page` envelope**, unlike `/customers` — `ConversationResponses.ConversationPage`
 * is hand-written and publishes `items` and a `total` rather than `content`, `totalElements` and
 * `totalPages`. The page count is arithmetic the client does, and this comment exists because the
 * two shapes are one word apart and a reader who has just left `lib/customers` will assume they
 * match.
 */
export interface ConversationPage {
  items: ConversationSummary[];
  page: number;
  size: number;
  total: number;
}

/**
 * One transcript line.
 *
 * `SYSTEM` never appears: the prompt is assembled per turn from configuration and is not persisted
 * as a message. What is persisted is the conversation — what was asked, what was answered, and
 * every tool that ran in between.
 *
 * The four nullable fields are nullable in a fixed pattern rather than independently: `toolName`
 * is non-null exactly when `role` is `TOOL` — the database enforces it with a `CHECK` — and
 * `content` is null on precisely those rows, because a tool row's payload is its arguments and its
 * result. An assistant row that only called a tool has no text of its own either.
 */
export interface ConversationMessage {
  id: string;
  role: 'USER' | 'ASSISTANT' | 'TOOL';
  content: string | null;
  toolName: string | null;
  /**
   * Passed through as the JSON they were stored as, deliberately — the point of the screen is to
   * show what actually crossed the boundary. A summarised tool call is exactly as useful as no
   * tool call when the Receptionist has done something surprising.
   */
  toolArguments: unknown;
  toolResult: unknown;
  createdAt: IsoInstant;
}

export interface ConversationDetail {
  conversation: ConversationSummary;
  messages: ConversationMessage[];
}
