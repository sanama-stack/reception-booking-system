'use client';

import { useState } from 'react';
import { Card, cn } from '@/components/ui';
import type {
  ConversationDetail,
  ConversationMessage,
  ConversationStatus,
} from '@/lib/conversations';
import { formatDateTime, formatTime, type Timezone } from '@/lib/time';

const STATUS: Record<ConversationStatus, string> = {
  ACTIVE: 'Still open',
  CLOSED: 'Ended',
  LIMIT_REACHED: 'Cut short by a limit',
};

/**
 * Every tool the Receptionist can call, in words an owner reads rather than the schema names a
 * model does.
 *
 * The raw name is shown beside the label rather than replaced by it. An owner reporting "it kept
 * calling find_available_slots" is quoting something searchable; a screen that only ever said
 * "Looked for free times" would have hidden the one string that makes a log grep-able.
 *
 * A tool absent from this map still renders — as its own name, which is the honest fallback for a
 * ninth tool a later phase adds and this map does not yet know about.
 */
const TOOL_LABELS: Record<string, string> = {
  get_services: 'Listed the services',
  get_service_details: 'Read a service',
  get_business_info: 'Read the business details',
  find_available_slots: 'Looked for free times',
  create_appointment: 'Booked an appointment',
  lookup_appointment: 'Looked up an appointment',
  cancel_appointment: 'Cancelled an appointment',
  reschedule_appointment: 'Moved an appointment',
};

export function Transcript({
  detail,
  timezone,
}: {
  detail: ConversationDetail;
  timezone: Timezone;
}) {
  const { conversation, messages } = detail;

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-4">
          <Fact label="Started" value={formatDateTime(conversation.startedAt, timezone)} />
          <Fact label="Status" value={STATUS[conversation.status]} />
          {/* "Rows", for the reason the list screen says: this counts tool traffic the customer
              never saw, and calling it "messages" overstates how much anybody typed. */}
          <Fact label="Rows" value={`${conversation.messageCount}`} />
          <Fact
            label="Tokens"
            value={group(conversation.promptTokens + conversation.completionTokens)}
            detail={`${group(conversation.promptTokens)} in · ${group(conversation.completionTokens)} out`}
          />
        </dl>
      </Card>

      {messages.length === 0 ? (
        <Card>
          <p className="text-ink-muted text-sm leading-relaxed">
            {/*
              TWO reasons a transcript is empty, and they are not the same thing to an owner
              looking for what the Receptionist said.

              A conversation row is written when the panel opens a session and the first message is
              what fills it, so a customer who opened a chat and closed the tab leaves one with
              nothing in it. The other is a transcript the retention purge took at ninety days.

              Without messagesPurgedAt the screen would have to guess between them from
              messageCount — which the purge does not decrement, and which sits in the Rows fact
              directly above this sentence. An owner reading "Rows 8" over "nothing was ever said
              in it" is reading a contradiction.

              The date is rendered and the length of the window is NOT. Ninety days lives in
              ConversationLimits on the server and is enforced only there; written down here too it
              would be a number duplicated across two languages with nothing checking the copies
              agree, and the one that drifted would be the one telling an owner a policy the system
              does not follow.
            */}
            {conversation.messagesPurgedAt
              ? `This conversation's transcript was deleted on ${formatDateTime(
                  conversation.messagesPurgedAt,
                  timezone,
                )}, under the transcript retention window. What it cost is kept; what was said in it is not.`
              : 'This conversation was opened but nothing was ever said in it.'}
          </p>
        </Card>
      ) : (
        <ol className="flex flex-col gap-3">
          {messages.map((message) => (
            <li key={message.id}>
              <Line message={message} timezone={timezone} />
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

/**
 * `12345` → `12,345`.
 *
 * Not `toLocaleString`, and not only because the lint rule forbids it: that rule exists to stop a
 * value being formatted against whatever locale the reader's browser happens to carry (ADR-0003),
 * and a token count grouped by periods for one owner and commas for another is the same defect in
 * a smaller place. Deterministic, like every other formatter in `lib/time`.
 */
function group(value: number): string {
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function Fact({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return (
    <div>
      <dt className="text-ink-muted text-xs font-medium tracking-wide uppercase">{label}</dt>
      <dd className="text-ink mt-1 text-sm font-medium">{value}</dd>
      {detail && <dd className="text-ink-muted mt-0.5 text-xs tabular-nums">{detail}</dd>}
    </div>
  );
}

function Line({ message, timezone }: { message: ConversationMessage; timezone: Timezone }) {
  if (message.role === 'TOOL') return <ToolCall message={message} timezone={timezone} />;

  const customer = message.role === 'USER';

  /**
   * An assistant row with no text at all.
   *
   * It happens on every turn where the model only called a tool, and it is worth rendering rather
   * than skipping: the sequence *is* the evidence on this screen, and a silently dropped row makes
   * two tool calls look like one decision.
   */
  if (!customer && !message.content) {
    return (
      <p className="text-ink-muted pl-3 text-xs italic">
        <Stamp instant={message.createdAt} timezone={timezone} /> The receptionist said nothing and
        called a tool.
      </p>
    );
  }

  return (
    <div
      className={cn(
        'rounded-lg border p-3',
        customer ? 'border-brand/25 bg-brand/[0.04]' : 'border-border bg-surface',
      )}
    >
      <p className="text-ink-muted mb-1 text-xs font-medium">
        {customer ? 'Customer' : 'Receptionist'}{' '}
        <Stamp instant={message.createdAt} timezone={timezone} />
      </p>
      <p className="text-ink text-sm leading-relaxed whitespace-pre-line">{message.content}</p>
    </div>
  );
}

/**
 * One tool call, collapsed to its name and expandable to what actually crossed the boundary.
 *
 * **Collapsed by default, and expandable rather than truncated.** A booking conversation is mostly
 * tool traffic, so leaving every payload open would bury the four sentences the customer read
 * under a wall of JSON — but a truncated payload is worse than none, because the field that
 * explains the bug is always the one past the cut. Both are avoided by folding rather than
 * clipping.
 */
function ToolCall({ message, timezone }: { message: ConversationMessage; timezone: Timezone }) {
  const [open, setOpen] = useState(false);
  const name = message.toolName ?? 'unknown tool';

  return (
    <div className="border-border bg-surface-muted rounded-lg border">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="text-ink flex w-full items-center gap-2 px-3 py-2 text-left text-sm"
      >
        <span aria-hidden className="text-ink-muted text-xs">
          {open ? '▾' : '▸'}
        </span>
        <span className="font-medium">{TOOL_LABELS[name] ?? name}</span>
        <code className="text-ink-muted text-xs">{name}</code>
        <span className="ml-auto">
          <Stamp instant={message.createdAt} timezone={timezone} />
        </span>
      </button>
      {open && (
        <div className="border-border flex flex-col gap-3 border-t px-3 py-3">
          <Payload label="Sent" value={message.toolArguments} />
          <Payload label="Returned" value={message.toolResult} />
        </div>
      )}
    </div>
  );
}

/**
 * The JSON, as it was stored.
 *
 * Not reformatted into fields and not summarised: what makes this screen the answer to "it told my
 * customer the wrong price" is that it shows the payload rather than somebody's reading of it. It
 * scrolls inside its own box, so a long slot list cannot make the page scroll sideways.
 */
function Payload({ label, value }: { label: string; value: unknown }) {
  return (
    <div>
      <p className="text-ink-muted text-xs font-medium tracking-wide uppercase">{label}</p>
      <pre className="border-border bg-surface text-ink mt-1 max-h-64 overflow-auto rounded-md border p-2 text-xs">
        {value === null || value === undefined ? '—' : JSON.stringify(value, null, 2)}
      </pre>
    </div>
  );
}

/**
 * The time of day, in the business's zone.
 *
 * Time only, not the date: a conversation happens inside a few minutes, and repeating the date on
 * forty rows would be forty copies of what the header already says.
 */
function Stamp({ instant, timezone }: { instant: string; timezone: Timezone }) {
  return (
    <span className="text-ink-muted text-xs tabular-nums">{formatTime(instant, timezone)}</span>
  );
}
