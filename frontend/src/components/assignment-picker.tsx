'use client';

import { useId } from 'react';
import { EmptyState, cn } from '@/components/ui';

export interface AssignmentOption {
  id: string;
  label: string;
  /** A second line — a job title, a duration and price. Optional because not every list has one. */
  hint?: string;
  /**
   * Marked rather than hidden. An inactive employee can still be assigned, and seeing that the
   * person a service depends on is switched off is the answer to "why is this not bookable".
   */
  inactive?: boolean;
}

/**
 * The multi-select behind both directions of an assignment.
 *
 * `PUT /services/{id}/employees` and `PUT /employees/{id}/services` are two views of one table, and
 * the server has a single writer for both so the two can never disagree about what a valid pair
 * is. This is the same idea on this side: one control, so the two screens cannot drift into
 * meaning different things by a tick.
 *
 * It is a replace, never an add-and-remove — which is exactly what a set of checkboxes knows: which
 * boxes are ticked now, not which ones changed.
 */
export function AssignmentPicker({
  legend,
  description,
  options,
  selected,
  onChange,
  emptyTitle,
  emptyDescription,
  emptyAction,
  disabled = false,
}: {
  legend: string;
  description?: string;
  options: AssignmentOption[];
  selected: string[];
  onChange: (next: string[]) => void;
  emptyTitle: string;
  emptyDescription: string;
  emptyAction?: React.ReactNode;
  disabled?: boolean;
}) {
  const groupId = useId();
  const chosen = new Set(selected);

  function toggle(id: string) {
    // Rebuilt in the order the options are listed, so the payload does not depend on the order the
    // boxes happened to be clicked in.
    const next = new Set(chosen);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    onChange(options.filter((option) => next.has(option.id)).map((option) => option.id));
  }

  return (
    <fieldset className="flex flex-col gap-2" aria-describedby={description ? groupId : undefined}>
      <legend className="text-ink text-sm font-medium">{legend}</legend>
      {description && (
        <p id={groupId} className="text-ink-muted text-sm">
          {description}
        </p>
      )}

      {options.length === 0 ? (
        <EmptyState
          title={emptyTitle}
          description={emptyDescription}
          {...(emptyAction ? { action: emptyAction } : {})}
          className="py-8"
        />
      ) : (
        <ul className="border-border divide-border divide-y rounded-md border">
          {options.map((option) => {
            const ticked = chosen.has(option.id);
            return (
              <li key={option.id}>
                <label
                  className={cn(
                    'flex cursor-pointer items-start gap-3 px-3 py-2.5 text-sm',
                    disabled && 'cursor-not-allowed opacity-50',
                  )}
                >
                  <input
                    type="checkbox"
                    checked={ticked}
                    disabled={disabled}
                    onChange={() => toggle(option.id)}
                    className="accent-brand mt-0.5 size-4 shrink-0"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="text-ink flex flex-wrap items-center gap-2 font-medium">
                      {option.label}
                      {option.inactive && (
                        <span className="text-ink-muted bg-surface-muted rounded px-1.5 py-0.5 text-xs font-normal">
                          Inactive
                        </span>
                      )}
                    </span>
                    {option.hint && (
                      <span className="text-ink-muted mt-0.5 block">{option.hint}</span>
                    )}
                  </span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
    </fieldset>
  );
}
