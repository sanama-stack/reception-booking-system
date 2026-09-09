import { cn } from '@/components/ui';
import type { AppointmentStatus } from '@/lib/appointments';

/**
 * An Appointment's status, in one set of words and one set of colours.
 *
 * The four tones are chosen from what each status *means to the business* rather than from whether
 * it sounds good. `CONFIRMED` is the brand colour because it is the only status that holds time
 * against the exclusion constraint — it is the live one, the row that stops the slot being sold
 * twice. `NO_SHOW` is `warning` rather than `danger` for the reason the token exists: nothing
 * failed, and somebody has to decide what to do about it.
 *
 * `CANCELLED` is deliberately the quietest. A cancelled appointment is history, its slot is back on
 * sale, and colouring it red would make every list of a busy month look like a list of problems.
 */
const TONES: Record<AppointmentStatus, string> = {
  CONFIRMED: 'border-brand/30 bg-brand/10 text-brand',
  COMPLETED: 'border-success/40 bg-success/10 text-ink',
  NO_SHOW: 'border-warning/40 bg-warning/10 text-ink',
  CANCELLED: 'border-border bg-surface-muted text-ink-muted',
};

const LABELS: Record<AppointmentStatus, string> = {
  CONFIRMED: 'Confirmed',
  COMPLETED: 'Completed',
  NO_SHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

export function StatusBadge({
  status,
  className,
}: {
  status: AppointmentStatus;
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium whitespace-nowrap',
        TONES[status],
        className,
      )}
    >
      {LABELS[status]}
    </span>
  );
}

/** The same words without the badge, for a sentence. */
export function statusLabel(status: AppointmentStatus): string {
  return LABELS[status];
}
