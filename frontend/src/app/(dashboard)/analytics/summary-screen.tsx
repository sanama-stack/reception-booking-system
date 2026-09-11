'use client';

import { EmptyState, ResourceGate, Table, Td, Th, cn } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import type { AnalyticsSummary } from '@/lib/analytics';
import { formatIsoDate, formatMoney } from '@/lib/time';

/**
 * One summary, keyed on its own question by the caller.
 *
 * Same rule as the appointments table and the calendar: a remount is what says *this is a new
 * question*, so last month's revenue can never sit under this month's heading while a failed
 * reload keeps the old data on screen.
 */
export function SummaryScreen({ path }: { path: string }) {
  const summary = useResource<AnalyticsSummary>(path);

  return (
    <ResourceGate resource={summary}>
      {(data) => (
        <div className="flex flex-col gap-6">
          <Headline summary={data} />
          <RightNow summary={data} />
          <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
            <Breakdown summary={data} />
            <TopServices summary={data} />
          </div>
        </div>
      )}
    </ResourceGate>
  );
}

/**
 * The four numbers about the range that was asked for.
 *
 * Revenue and the two rates each carry the thing that makes them true rather than merely large: what
 * revenue counts, and what a rate was divided by. A percentage with no denominator beside it is how
 * "50% cancellation" turns out to be one appointment out of two.
 */
function Headline({ summary }: { summary: AnalyticsSummary }) {
  const { counts, rates, revenue } = summary;

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Metric
        label="Appointments"
        value={String(counts.total)}
        note={`${formatIsoDate(summary.range.from)} – ${formatIsoDate(summary.range.to)}`}
      />
      <Metric
        label="Revenue"
        value={formatMoney(revenue.amount, revenue.currency)}
        /*
          Two caveats, both of them load-bearing. Completed only, because a booking that was never
          honoured is not money. And this currency only: appointments keep the currency they were
          priced in, so a business that switched has older revenue this figure does not report —
          saying so is what stops a smaller number from reading as a bad month.
        */
        note={`Completed appointments only, priced in ${revenue.currency}`}
      />
      <Rate label="Cancellation rate" rate={rates.cancellation} of={counts.total} />
      <Rate label="No-show rate" rate={rates.noShow} of={counts.total} />
    </div>
  );
}

/**
 * The live counts, fenced off from the range.
 *
 * They are deliberately not bounded by `from`/`to` — the server's decision, and the right one:
 * bounded, a report on last September would answer "today: 0" for everybody. But an unlabelled
 * "Today: 3" sitting beside a report about March is a number that means something other than it
 * appears to, so the heading says *now* and the range is nowhere near it.
 */
function RightNow({ summary }: { summary: AnalyticsSummary }) {
  const { periods } = summary;

  return (
    <section className="border-border bg-surface rounded-lg border p-6 shadow-sm">
      <header className="mb-4">
        <h2 className="text-ink text-base font-semibold">Right now</h2>
        <p className="text-ink-muted mt-1 text-sm">
          Live counts in {summary.range.timezone}, whatever range is selected above. The week starts
          on Monday.
        </p>
      </header>
      <div className="grid grid-cols-3 gap-4">
        <Metric label="Today" value={String(periods.today)} bare />
        <Metric label="This week" value={String(periods.thisWeek)} bare />
        <Metric label="This month" value={String(periods.thisMonth)} bare />
      </div>
    </section>
  );
}

const STATUS_ROWS = [
  { key: 'confirmed', label: 'Confirmed', tone: 'bg-brand' },
  { key: 'completed', label: 'Completed', tone: 'bg-success' },
  { key: 'noShow', label: 'No-show', tone: 'bg-warning' },
  { key: 'cancelled', label: 'Cancelled', tone: 'bg-ink-muted' },
] as const;

/**
 * Where the range's appointments ended up.
 *
 * A bar per status, drawn as a share of the total rather than of the largest row: the question is
 * "how much of my month was this", and a bar scaled to the biggest row answers a different one by
 * making every breakdown look equally dramatic.
 */
function Breakdown({ summary }: { summary: AnalyticsSummary }) {
  const { counts } = summary;

  return (
    <section className="border-border bg-surface rounded-lg border p-6 shadow-sm">
      <header className="mb-4">
        <h2 className="text-ink text-base font-semibold">How they ended</h2>
        <p className="text-ink-muted mt-1 text-sm">
          Every appointment starting in this range, by the status it is in now.
        </p>
      </header>

      {counts.total === 0 ? (
        <EmptyState
          title="Nothing in this range"
          description="No appointment starts between those two dates. A wider range, or a range that has already happened, is usually what is wanted."
        />
      ) : (
        <ul className="flex flex-col gap-3">
          {STATUS_ROWS.map((row) => {
            const count = counts[row.key];
            const share = count / counts.total;
            return (
              <li key={row.key} className="flex flex-col gap-1">
                <div className="flex items-baseline justify-between gap-3 text-sm">
                  <span className="text-ink">{row.label}</span>
                  <span className="text-ink-muted tabular-nums">
                    {count} · {percent(share)}
                  </span>
                </div>
                <div className="bg-surface-muted h-2 overflow-hidden rounded-full">
                  <div
                    className={cn('h-full rounded-full', row.tone)}
                    style={{ width: `${share * 100}%` }}
                  />
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

/** The ranking. Five at most, already ordered by the server, ties broken deterministically there. */
function TopServices({ summary }: { summary: AnalyticsSummary }) {
  return (
    <section className="border-border bg-surface rounded-lg border p-6 shadow-sm">
      <header className="mb-4">
        <h2 className="text-ink text-base font-semibold">Most booked</h2>
        <p className="text-ink-muted mt-1 text-sm">
          The five most booked services in this range. A service you have since deactivated still
          appears, because it was still booked.
        </p>
      </header>

      {summary.topServices.length === 0 ? (
        <EmptyState
          title="No services booked"
          description="Nothing in this range to rank. This is the same emptiness as the breakdown beside it, not a second problem."
        />
      ) : (
        <Table>
          <thead>
            <tr>
              <Th>Service</Th>
              <Th>Booked</Th>
            </tr>
          </thead>
          <tbody>
            {summary.topServices.map((service) => (
              <tr key={service.serviceId}>
                <Td className="text-ink">{service.name}</Td>
                <Td className="tabular-nums">{service.count}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </section>
  );
}

function Metric({
  label,
  value,
  note,
  bare,
}: {
  label: string;
  value: string;
  note?: string;
  bare?: boolean;
}) {
  return (
    <div
      className={cn(
        'flex flex-col gap-1',
        !bare && 'border-border bg-surface rounded-lg border p-5 shadow-sm',
      )}
    >
      <span className="text-ink-muted text-sm">{label}</span>
      <span className="text-ink text-2xl font-semibold tabular-nums">{value}</span>
      {note && <span className="text-ink-muted text-xs">{note}</span>}
    </div>
  );
}

/**
 * A rate, or an honest blank.
 *
 * **`null` is not zero and must never render as `0%`.** It means there was nothing to divide by, and
 * "0% cancellation" from an empty month is a number an owner would act on — the server goes out of
 * its way to send `null` rather than `0` precisely so this screen can say nothing instead.
 */
function Rate({ label, rate, of }: { label: string; rate: number | null; of: number }) {
  return (
    <div className="border-border bg-surface flex flex-col gap-1 rounded-lg border p-5 shadow-sm">
      <span className="text-ink-muted text-sm">{label}</span>
      <span className="text-ink text-2xl font-semibold tabular-nums">
        {rate === null ? '—' : percent(rate)}
      </span>
      <span className="text-ink-muted text-xs">
        {rate === null ? 'No appointments to measure' : `of ${of} appointments`}
      </span>
    </div>
  );
}

/** `0.073` → `7.3%`. One decimal: three came over the wire, and a tenth of a percent is plenty. */
function percent(fraction: number): string {
  return `${(fraction * 100).toFixed(1)}%`;
}
