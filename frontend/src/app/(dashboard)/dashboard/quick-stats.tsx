'use client';

import { ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { analyticsSummaryPath, type AnalyticsSummary } from '@/lib/analytics';
import { formatMoney, toBusinessDate, type Timezone } from '@/lib/time';

/**
 * Three numbers and what today earned.
 *
 * The counts come from the summary's **periods**, which are deliberately not bounded by the range
 * asked for — so the range here is today, and it is the revenue figure that uses it. Asking for a
 * one-day range to read three unbounded counts looks odd until you know that; it is one request
 * where the alternative is two, and the revenue would otherwise have nothing to describe.
 */
export function QuickStats({ timezone }: { timezone: Timezone }) {
  const today = toBusinessDate(new Date(), timezone);
  const summary = useResource<AnalyticsSummary>(analyticsSummaryPath(today, today));

  return (
    <ResourceGate resource={summary}>
      {(data) => (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <Stat label="Today" value={String(data.periods.today)} />
          <Stat label="This week" value={String(data.periods.thisWeek)} />
          <Stat label="This month" value={String(data.periods.thisMonth)} />
          {/*
            Completed only, and in the business's current currency — the same two caveats the
            analytics screen spells out. Here it is one line under one number, because a home
            screen that explains every figure is a home screen nobody reads.
          */}
          <Stat
            label="Earned today"
            value={formatMoney(data.revenue.amount, data.revenue.currency)}
            note="Completed only"
          />
        </div>
      )}
    </ResourceGate>
  );
}

function Stat({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="border-border bg-surface flex flex-col gap-1 rounded-lg border p-4 shadow-sm">
      <span className="text-ink-muted text-sm">{label}</span>
      <span className="text-ink text-xl font-semibold tabular-nums">{value}</span>
      {note && <span className="text-ink-muted text-xs">{note}</span>}
    </div>
  );
}
