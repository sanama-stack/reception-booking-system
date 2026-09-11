'use client';

import { useState } from 'react';
import { Button, Card, Input } from '@/components/ui';
import { analyticsSummaryPath } from '@/lib/analytics';
import { useSession } from '@/lib/auth';
import { addIsoDays, toBusinessDate, type IsoDate } from '@/lib/time';
import { SummaryScreen } from './summary-screen';

/**
 * The numbers.
 *
 * One endpoint and one screen, which is the server's design and not an accident of this page: a
 * dashboard that fetched counts, revenue and a ranking separately renders in three stages and can
 * show three different moments of the same business.
 *
 * **Every boundary here is a date in the business's timezone**, never the browser's. "Last 30 days"
 * for a salon in Auckland ends on Auckland's today, whoever is reading it and wherever they are
 * (ADR-0003).
 */
export default function AnalyticsPage() {
  const { session } = useSession();
  const timezone = session?.business.timezone;

  const [range, setRange] = useState<{ from: IsoDate; to: IsoDate } | null>(null);

  if (!session || !timezone) return null;

  const today = toBusinessDate(new Date(), timezone);
  const presets = presetsFor(today);
  const selected = range ?? presets[1]!.range;
  const path = analyticsSummaryPath(selected.from, selected.to);

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">Analytics</h1>
        <p className="text-ink-muted mt-1 text-sm">
          What was booked, what was honoured, and what it earned. Days are cut in {timezone}.
        </p>
      </div>

      <Card>
        <div className="flex flex-col gap-4">
          <div className="flex flex-wrap gap-2">
            {presets.map((preset) => {
              const active = selected.from === preset.range.from && selected.to === preset.range.to;
              return (
                <Button
                  key={preset.label}
                  variant={active ? 'primary' : 'secondary'}
                  size="sm"
                  aria-pressed={active}
                  onClick={() => setRange(preset.range)}
                >
                  {preset.label}
                </Button>
              );
            })}
          </div>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input
              label="From"
              type="date"
              value={selected.from}
              onChange={(event) =>
                event.target.value && setRange({ ...selected, from: event.target.value })
              }
            />
            <Input
              label="To"
              type="date"
              value={selected.to}
              min={selected.from}
              onChange={(event) =>
                event.target.value && setRange({ ...selected, to: event.target.value })
              }
              hint="Inclusive — the last day you pick is a day that counts."
            />
          </div>
        </div>
      </Card>

      {/*
        Keyed on the question. A remount is what puts the loading state back on screen when the
        range changes, instead of leaving last month's revenue under this month's dates while the
        new answer is in flight — and it is what shows the server's `422` for a backwards or
        year-long range rather than a stale success.
      */}
      <SummaryScreen key={path} path={path} />
    </div>
  );
}

interface Preset {
  label: string;
  range: { from: IsoDate; to: IsoDate };
}

/**
 * The four ranges an owner actually asks for, all of them ending today.
 *
 * "Last 7 days" includes today, which is why it counts back six: a range from today minus seven to
 * today is eight days, and a preset that quietly asks a different question than its label is worse
 * than no preset. The second is the default, because a month is the window a business plans in.
 */
function presetsFor(today: IsoDate): Preset[] {
  const thisMonthStart = startOfMonth(today);
  const lastMonthEnd = addIsoDays(thisMonthStart, -1);

  return [
    { label: 'Last 7 days', range: { from: addIsoDays(today, -6), to: today } },
    { label: 'Last 30 days', range: { from: addIsoDays(today, -29), to: today } },
    { label: 'This month', range: { from: thisMonthStart, to: today } },
    { label: 'Last month', range: { from: startOfMonth(lastMonthEnd), to: lastMonthEnd } },
  ];
}

/**
 * The first of the month an `IsoDate` falls in.
 *
 * Sliced from the string rather than parsed into a `Date`: `new Date('2026-09-11')` is UTC midnight,
 * which is still August for a business west of Greenwich — the off-by-one-day `lib/time` exists to
 * prevent, and the reason an `IsoDate` is treated as three numbers rather than as an instant.
 */
function startOfMonth(date: IsoDate): IsoDate {
  return `${date.slice(0, 7)}-01`;
}
