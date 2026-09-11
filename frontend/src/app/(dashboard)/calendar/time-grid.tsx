'use client';

import { cn } from '@/components/ui';
import { DAY_MINUTES, minuteLabel, type GridWindow, type Span } from './geometry';

/**
 * The grid chrome: an hour gutter, hour lines, columns, and the line showing now.
 *
 * **This is the only file that turns a minute into a pixel.** `geometry.ts` decides where things
 * sit in the day and this decides how tall a day is; every block on either view is positioned by
 * the same two lines of arithmetic here, which is what makes a 150-minute service look three times
 * a 50-minute one on both views rather than on the one that was checked.
 */

/** One pixel a minute — an hour row is 60px, and a 45-minute service is 45px of colour. */
const PIXELS_PER_MINUTE = 1;

export interface GridItem {
  key: string;
  span: Span;
  /** Which sub-column, from `placeSideBySide`. Background regions pass 0 of 1 and fill the width. */
  lane: number;
  lanes: number;
  node: React.ReactNode;
}

export interface GridColumn {
  key: string;
  header: React.ReactNode;
  /** Non-bookable regions — closures and time off — drawn behind everything. */
  regions: GridItem[];
  items: GridItem[];
  /**
   * Clicking empty space in this column — a day, or a day and a person.
   *
   * **It carries no minute, and that is not an oversight.** A minute on a grid is not a bookable
   * time: which times exist depends on the service's duration, its buffers, the employee's working
   * hours and what is already booked, all of which the availability engine answers and none of
   * which this grid knows. Reporting `10:07` would only invite a caller to book it.
   */
  onPick?: () => void;
  pickLabel?: string;
}

export function TimeGrid({
  window,
  columns,
  now,
}: {
  window: GridWindow;
  columns: GridColumn[];
  /**
   * Where to draw the current-time line, and in which column. A `columnKey` of `null` means every
   * column is the same day — the day view — so the line crosses all of them. In the week view only
   * today's column gets one, because 14:20 on Thursday is not a fact about Tuesday.
   */
  now: { minute: number; columnKey: string | null } | null;
}) {
  const height = (window.endMinute - window.startMinute) * PIXELS_PER_MINUTE;

  const hours: number[] = [];
  for (let minute = window.startMinute; minute <= window.endMinute; minute += 60)
    hours.push(minute);

  const top = (minute: number) => (clamp(minute, window) - window.startMinute) * PIXELS_PER_MINUTE;

  function positionOf(item: GridItem): React.CSSProperties {
    const start = top(item.span.startMinute);
    const end = top(item.span.endMinute);
    const width = 100 / item.lanes;
    return {
      top: `${start}px`,
      // Never zero: a span clipped to a sliver by the window still has to be visible as something.
      height: `${Math.max(end - start, 12)}px`,
      left: `${item.lane * width}%`,
      width: `${width}%`,
    };
  }

  return (
    <div className="border-border bg-surface overflow-x-auto rounded-lg border">
      <div className="flex min-w-max">
        <div className="bg-surface sticky left-0 z-20 w-14 shrink-0 border-r border-[color:var(--color-border)]">
          <div className="border-border h-12 border-b" />
          <div className="relative" style={{ height: `${height}px` }}>
            {hours.map((minute) => (
              <span
                key={minute}
                className="text-ink-muted absolute -translate-y-1/2 pr-2 text-right text-[11px] tabular-nums"
                style={{ top: `${top(minute)}px`, right: 0 }}
              >
                {minuteLabel(minute)}
              </span>
            ))}
          </div>
        </div>

        {columns.map((column) => (
          <div
            key={column.key}
            className="border-border min-w-[9rem] flex-1 border-r last:border-r-0"
          >
            <div className="border-border bg-surface sticky top-0 z-10 flex h-12 items-center justify-center border-b px-2 text-center">
              {column.header}
            </div>

            <div className="relative" style={{ height: `${height}px` }}>
              {hours.map((minute) => (
                <div
                  key={minute}
                  aria-hidden="true"
                  className="border-border/70 absolute inset-x-0 border-t"
                  style={{ top: `${top(minute)}px` }}
                />
              ))}

              {column.regions.map((region) => (
                <div key={region.key} className="absolute z-0 px-px" style={positionOf(region)}>
                  {region.node}
                </div>
              ))}

              {/*
                Behind the blocks rather than around them, because a block is itself a button and
                nesting one inside another is invalid HTML that browsers resolve by dropping one of
                the two — usually the one you wanted.
              */}
              {column.onPick && (
                <button
                  type="button"
                  aria-label={column.pickLabel ?? 'Book an appointment'}
                  className="focus-visible:ring-brand absolute inset-0 z-[1] cursor-copy rounded-none focus-visible:ring-2 focus-visible:outline-none focus-visible:ring-inset"
                  onClick={() => column.onPick?.()}
                />
              )}

              {column.items.map((item) => (
                <div key={item.key} className="absolute z-10 px-px" style={positionOf(item)}>
                  {item.node}
                </div>
              ))}

              {now && (now.columnKey === null || now.columnKey === column.key) && (
                <CurrentTime top={top(now.minute)} labelled={now.columnKey !== null} />
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * The line showing now.
 *
 * Drawn only when the window actually contains this minute — the caller decides that — so it never
 * pins itself to the top of the grid at 06:00 and claims to be the current time.
 */
function CurrentTime({ top, labelled }: { top: number; labelled: boolean }) {
  return (
    <div
      aria-hidden="true"
      className={cn('pointer-events-none absolute inset-x-0 z-20 flex items-center')}
      style={{ top: `${top}px` }}
    >
      <span className="bg-danger -ml-1 size-2 shrink-0 rounded-full" />
      <span className="bg-danger h-px flex-1" />
      {labelled && <span className="sr-only">Now</span>}
    </div>
  );
}

function clamp(minute: number, window: GridWindow): number {
  return Math.min(Math.max(minute, window.startMinute), Math.min(window.endMinute, DAY_MINUTES));
}
