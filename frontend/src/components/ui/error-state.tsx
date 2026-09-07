import type { ApiError } from '@/lib/api/client';
import { Button } from './button';
import { cn } from './cn';

/**
 * Renders the server's message rather than inventing one — every error body carries a `detail`
 * written for a human (docs/04-api-overview.md §3).
 */
export function ErrorState({
  error,
  onRetry,
  className,
}: {
  error: ApiError | string;
  onRetry?: () => void;
  className?: string;
}) {
  const message = typeof error === 'string' ? error : error.message;
  const code = typeof error === 'string' ? undefined : error.code;

  return (
    <div
      role="alert"
      className={cn(
        'border-danger/30 bg-danger/5 flex flex-col items-start gap-3 rounded-lg border p-4',
        className,
      )}
    >
      <div>
        <p className="text-ink text-sm font-medium">Something went wrong</p>
        <p className="text-ink-muted mt-1 text-sm">{message}</p>
        {code && <p className="text-ink-muted mt-1 font-mono text-xs">{code}</p>}
      </div>
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}
