'use client';

import { useId } from 'react';
import { cn } from './cn';

export interface TextareaProps extends React.TextareaHTMLAttributes<HTMLTextAreaElement> {
  label: string;
  /** Server-provided message from problem+json `errors`, rendered verbatim. */
  error?: string;
  hint?: string;
}

export function Textarea({ label, error, hint, id, className, rows = 4, ...props }: TextareaProps) {
  const generatedId = useId();
  const textareaId = id ?? generatedId;
  const describedBy = error ? `${textareaId}-error` : hint ? `${textareaId}-hint` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={textareaId} className="text-ink text-sm font-medium">
        {label}
      </label>
      <textarea
        {...props}
        id={textareaId}
        rows={rows}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'border-border bg-surface text-ink rounded-md border px-3 py-2 text-sm',
          'placeholder:text-ink-muted disabled:opacity-50',
          error && 'border-danger',
          className,
        )}
      />
      {error ? (
        <p id={`${textareaId}-error`} className="text-danger text-sm">
          {error}
        </p>
      ) : hint ? (
        <p id={`${textareaId}-hint`} className="text-ink-muted text-sm">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
