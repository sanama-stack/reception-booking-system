import { cn } from './cn';

/**
 * A table that scrolls sideways inside its own box rather than making the page do it.
 *
 * **`relative` is load-bearing, not styling.** A `sr-only` label — the invisible "Actions" on a
 * column of buttons — is `position: absolute`, and an absolutely positioned element is only clipped
 * by an ancestor with `overflow` if that ancestor is *positioned*. Without `relative` here, that
 * one-pixel span took its position from the table's full 554px width, escaped this container
 * entirely, and gave the whole page a horizontal scrollbar at 360px: the services screen was 466px
 * wide on a 360px phone because of a label nobody can see.
 */
export function Table({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className="border-border bg-surface relative overflow-x-auto rounded-lg border">
      <table className={cn('w-full border-collapse text-sm', className)}>{children}</table>
    </div>
  );
}

export function Th({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <th
      scope="col"
      className={cn(
        'border-border text-ink-muted border-b px-4 py-2.5 text-left text-xs font-medium tracking-wide uppercase',
        className,
      )}
    >
      {children}
    </th>
  );
}

export function Td({ children, className }: { children: React.ReactNode; className?: string }) {
  return <td className={cn('border-border text-ink border-b px-4 py-3', className)}>{children}</td>;
}
