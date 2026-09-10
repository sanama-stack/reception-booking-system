import Link from 'next/link';
import { cn } from './cn';
import { Spinner } from './spinner';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-brand text-brand-contrast hover:opacity-90',
  secondary: 'bg-surface text-ink border border-border hover:bg-surface-muted',
  ghost: 'text-ink hover:bg-surface-muted',
  danger: 'bg-danger text-brand-contrast hover:opacity-90',
};

/**
 * `md` is 44 px, the smallest target a thumb hits reliably, because the surfaces that matter most
 * for target size are the public ones — `/book/{slug}` and `/manage/{token}` — and they take the
 * default. It was the other way round until issue #11: the shared control was 40 px and every
 * public screen overrode it with `min-h-11`, four files deep with the same comment written twice.
 * A guideline applied by remembering is not applied.
 *
 * `sm` stays 32 px and is for dense, mouse-operated dashboard rows. A small button on a thumb
 * surface pairs it with `min-h-11`, which sets a different property and so wins without fighting
 * the size class — see `fortnight-picker`'s pager.
 */
const SIZES: Record<Size, string> = {
  sm: 'h-8 px-3 text-sm',
  md: 'h-11 px-4 text-sm',
};

/** The one place a button's appearance is decided, so a link that acts as one cannot drift. */
function appearance(variant: Variant, size: Size, className?: string): string {
  return cn(
    'inline-flex items-center justify-center gap-2 rounded-md font-medium transition',
    'disabled:cursor-not-allowed disabled:opacity-50',
    VARIANTS[variant],
    SIZES[size],
    className,
  );
}

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
}

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  disabled,
  className,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={appearance(variant, size, className)}
    >
      {loading && <Spinner className="size-4" />}
      {children}
    </button>
  );
}

/**
 * A link that looks like a button, for an action that is a navigation.
 *
 * It is a real `<a>` rather than a button with an `onClick` that pushes: "New service" opens a
 * page, so it should be middle-clickable, openable in a new tab, and announced as a link. A button
 * that navigates takes all three away for no gain.
 */
export function ButtonLink({
  href,
  variant = 'primary',
  size = 'md',
  className,
  children,
}: {
  href: string;
  variant?: Variant;
  size?: Size;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <Link href={href} className={appearance(variant, size, className)}>
      {children}
    </Link>
  );
}
