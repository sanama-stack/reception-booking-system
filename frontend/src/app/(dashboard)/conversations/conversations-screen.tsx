'use client';

import Link from 'next/link';
import { Button, EmptyState, ResourceGate, Table, Td, Th, cn } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { ConversationPage, ConversationStatus } from '@/lib/conversations';
import { formatDateTime, type Timezone } from '@/lib/time';

/**
 * The three states a conversation ends in, and what each one means to the owner.
 *
 * They are **not** the appointment tones and do not reuse `StatusBadge`: nothing here is confirmed
 * or cancelled, and the one that deserves attention is `LIMIT_REACHED` — the daily cost cap
 * stopped a customer mid-sentence, which is the only row on this screen that says the owner should
 * do something. `CLOSED` is the ordinary ending and is coloured like one.
 */
const TONES: Record<ConversationStatus, string> = {
  ACTIVE: 'border-brand/30 bg-brand/10 text-brand',
  CLOSED: 'border-border bg-surface-muted text-ink-muted',
  LIMIT_REACHED: 'border-warning/40 bg-warning/10 text-ink',
};

const LABELS: Record<ConversationStatus, string> = {
  ACTIVE: 'Active',
  CLOSED: 'Ended',
  LIMIT_REACHED: 'Cut short',
};

export function ConversationsScreen({
  path,
  timezone,
  onPage,
}: {
  path: string;
  /**
   * The business's zone, passed down rather than read off the response.
   *
   * `/conversations` carries no zone because nothing it returns is a business-local time — a
   * conversation starts at an instant. It is still *rendered* in the business's zone like
   * everything else, rather than in whatever zone the owner's laptop is set to (ADR-0003).
   */
  timezone: Timezone;
  onPage: (page: number) => void;
}) {
  const conversations = useResource<ConversationPage>(path);

  return (
    <ResourceGate resource={conversations}>
      {(result) => {
        if (result.items.length === 0) {
          return (
            <EmptyState
              title="No conversations yet"
              description="A conversation appears here the first time somebody speaks to the receptionist on your booking page. Nothing is recorded for a visitor who only reads the page."
            />
          );
        }

        // `total` and `size`, not a `totalPages` the server sends: this envelope is hand-written
        // and publishes neither `totalPages` nor `content`, unlike the Spring page `/customers`
        // returns. See `lib/conversations/types.ts`.
        const totalPages = Math.max(1, Math.ceil(result.total / result.size));
        const first = result.page * result.size + 1;

        return (
          <div className="flex flex-col gap-4">
            <Table>
              <thead>
                <tr>
                  <Th>Started</Th>
                  <Th>Status</Th>
                  <Th>Rows</Th>
                  <Th>Cost</Th>
                  <Th>Last activity</Th>
                </tr>
              </thead>
              <tbody>
                {result.items.map((conversation) => (
                  <tr key={conversation.id}>
                    <Td>
                      <Link
                        href={`/conversations/${conversation.id}`}
                        className="text-ink font-medium whitespace-nowrap hover:underline"
                      >
                        {formatDateTime(conversation.startedAt, timezone)}
                      </Link>
                    </Td>
                    <Td>
                      <span
                        className={cn(
                          'inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium whitespace-nowrap',
                          TONES[conversation.status],
                        )}
                      >
                        {LABELS[conversation.status]}
                      </span>
                    </Td>
                    {/*
                      "Rows", not "messages". The count includes the tool calls and tool results the
                      customer never saw, so labelling it as messages would tell an owner their
                      customer typed three times as often as they did.
                    */}
                    <Td className="text-ink-muted tabular-nums">
                      {/* A conversation opened and abandoned has exactly one row, and it is the
                          commonest row on this screen — so "1 rows" is the case that shows, not
                          the edge case that does not. */}
                      {conversation.messageCount} {conversation.messageCount === 1 ? 'row' : 'rows'}
                    </Td>
                    <Td className="text-ink-muted whitespace-nowrap tabular-nums">
                      {formatCents(conversation.estimatedCostCents)}
                    </Td>
                    <Td className="text-ink-muted whitespace-nowrap">
                      {formatDateTime(conversation.lastMessageAt, timezone)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>

            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-ink-muted text-sm tabular-nums">
                {first}–{first + result.items.length - 1} of {result.total}
              </p>
              {totalPages > 1 && (
                <div className="flex items-center gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={result.page === 0}
                    onClick={() => onPage(result.page - 1)}
                  >
                    Previous
                  </Button>
                  <span className="text-ink-muted text-sm tabular-nums">
                    Page {result.page + 1} of {totalPages}
                  </span>
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={result.page + 1 >= totalPages}
                    onClick={() => onPage(result.page + 1)}
                  >
                    Next
                  </Button>
                </div>
              )}
            </div>
          </div>
        );
      }}
    </ResourceGate>
  );
}

/**
 * Cents, as money.
 *
 * **Not `formatMoney`.** That one takes a currency and renders a price a customer is charged; this
 * is an internal estimate of what a conversation cost to run, always in the provider's currency
 * and never in the business's. Rendering it through the same helper would put a `£` in front of a
 * number of US cents.
 *
 * **Zero means no model call was ever made, not "less than a cent".** `CostTracker.costCentsFor`
 * rounds *up*, deliberately — rounding down would let a long series of cheap turns accumulate to
 * nothing and never reach the cap — so any turn that reached the provider costs at least one cent.
 * A zero is a conversation that was opened and cut short before it spent anything, and a dash says
 * that better than `$0.00` does.
 */
function formatCents(cents: number): string {
  if (cents === 0) return '—';
  return `$${(cents / 100).toFixed(2)}`;
}
