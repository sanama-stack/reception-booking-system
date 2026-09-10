'use client';

import { useId } from 'react';
import { cn } from './cn';

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string;
  /** Server-provided message from problem+json `errors`, rendered verbatim. */
  error?: string;
  hint?: string;
}

export function Input({ label, error, hint, id, className, ...props }: InputProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const describedBy = error ? `${inputId}-error` : hint ? `${inputId}-hint` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={inputId} className="text-ink text-sm font-medium">
        {label}
      </label>
      <input
        {...props}
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'border-border bg-surface text-ink h-11 rounded-md border px-3 text-sm',
          'placeholder:text-ink-muted disabled:opacity-50',
          error && 'border-danger',
          className,
        )}
      />
      {error ? (
        <p id={`${inputId}-error`} className="text-danger text-sm">
          {error}
        </p>
      ) : hint ? (
        <p id={`${inputId}-hint`} className="text-ink-muted text-sm">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
