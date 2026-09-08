'use client';

import type { ActorType, HistoryEntry } from '@/lib/appointments';
import { formatDateTime, type Timezone } from '@/lib/time';

/**
 * The audit trail, one line per transition.
 *
 * Every state change writes one of these, which is what makes this list the answer to "who moved
 * my appointment, and what was it before?" — a question the appointment row itself cannot answer,
 * because an update overwrites the times it is being asked about.
 *
 * **The instants inside `payload` are UTC**, unlike every field on the appointment: the recorder
 * writes `Instant.toString()`, so they arrive with a `Z` rather than the business's offset. They
 * are rendered through the envelope's timezone like any other instant, which is the whole reason
 * `lib/time` refuses to format anything without being handed a zone (ADR-0003).
 */
const ACTORS: Record<ActorType, string> = {
  USER: 'by the business',
  CUSTOMER: 'by the customer',
  AI: 'by the receptionist',
  SYSTEM: 'automatically',
};

export function HistoryList({
  history,
  timezone,
}: {
  history: HistoryEntry[];
  timezone: Timezone;
}) {
  return (
    <ol className="flex flex-col gap-3">
      {history.map((entry) => {
        const line = detail(entry, timezone);
        return (
          <li key={entry.id} className="border-border flex flex-col gap-0.5 border-l-2 pl-4">
            <p className="text-ink text-sm font-medium">{headline(entry)}</p>
            {line && <p className="text-ink-muted text-sm">{line}</p>}
            <p className="text-ink-muted text-xs">
              {formatDateTime(entry.at, timezone)} · {ACTORS[entry.actorType]}
            </p>
          </li>
        );
      })}
    </ol>
  );
}

function headline(entry: HistoryEntry): string {
  switch (entry.type) {
    case 'CREATED':
      return 'Booked';
    case 'RESCHEDULED':
      return 'Moved';
    case 'CANCELLED':
      return 'Cancelled';
    case 'COMPLETED':
      return 'Marked completed';
    case 'NO_SHOW':
      return 'Marked as a no-show';
    default:
      // A sixth event type should be visible rather than silently blank.
      return entry.type;
  }
}

function detail(entry: HistoryEntry, timezone: Timezone): string | null {
  switch (entry.type) {
    case 'CREATED': {
      const source = text(entry.payload.source);
      return source ? `Taken ${describeSource(source)}.` : null;
    }
    case 'RESCHEDULED': {
      const previous = text(entry.payload.previousStartsAt);
      const next = text(entry.payload.startsAt);
      if (!previous || !next) return null;
      const movedPerson = text(entry.payload.previousEmployeeId) !== text(entry.payload.employeeId);
      return `From ${formatDateTime(previous, timezone)} to ${formatDateTime(next, timezone)}${
        movedPerson ? ', and to a different person.' : '.'
      }`;
    }
    case 'CANCELLED': {
      const by = text(entry.payload.cancelledBy);
      const reason = text(entry.payload.reason);
      const who = by === 'CUSTOMER' ? 'The customer cancelled' : 'The business cancelled';
      return reason ? `${who}: ${reason}` : `${who}.`;
    }
    default: {
      const from = text(entry.payload.from);
      const to = text(entry.payload.to);
      return from && to ? `${from} → ${to}` : null;
    }
  }
}

function describeSource(source: string): string {
  switch (source) {
    case 'DASHBOARD':
      return 'on this dashboard';
    case 'CLASSIC':
      return 'through the booking page';
    case 'AI':
      return 'by the receptionist';
    default:
      return `from ${source}`;
  }
}

/** `payload` is arbitrary `jsonb`, so nothing in it may be assumed to be a string. */
function text(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}
