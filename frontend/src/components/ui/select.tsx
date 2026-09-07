'use client';

import { useId } from 'react';
import { cn } from './cn';

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  /** Server-provided message from problem+json `errors`, rendered verbatim. */
  error?: string;
  hint?: string;
}

/** The `Input` shape, for a choice from a fixed list. Kept in step with it deliberately. */
export function Select({ label, error, hint, id, className, children, ...props }: SelectProps) {
  const generatedId = useId();
  const selectId = id ?? generatedId;
  const describedBy = error ? `${selectId}-error` : hint ? `${selectId}-hint` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={selectId} className="text-ink text-sm font-medium">
        {label}
      </label>
      <select
        {...props}
        id={selectId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'border-border bg-surface text-ink h-10 rounded-md border px-3 text-sm',
          'disabled:opacity-50',
          error && 'border-danger',
          className,
        )}
      >
        {children}
      </select>
      {error ? (
        <p id={`${selectId}-error`} className="text-danger text-sm">
          {error}
        </p>
      ) : hint ? (
        <p id={`${selectId}-hint`} className="text-ink-muted text-sm">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
