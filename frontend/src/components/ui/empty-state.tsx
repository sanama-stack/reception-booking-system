import { cn } from './cn';

/**
 * Every screen ships with empty, loading and error states. They are part of a screen's definition
 * of done, not phase-11 polish (docs/09-phase-plan.md §5).
 */
export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'border-border flex flex-col items-center gap-2 rounded-lg border border-dashed px-6 py-12 text-center',
        className,
      )}
    >
      <p className="text-ink text-sm font-medium">{title}</p>
      {description && <p className="text-ink-muted max-w-sm text-sm">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}
