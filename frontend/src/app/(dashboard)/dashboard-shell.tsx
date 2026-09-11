'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Button, cn } from '@/components/ui';
import { useSession } from '@/lib/auth';

/**
 * Sidebar, header and sign-out.
 *
 * The navigation lists every dashboard section from the phase plan, with the ones that do not
 * exist yet marked rather than hidden. An owner seeing "Services — phase 04" understands the
 * product is being built; an owner clicking a link that 404s does not.
 */
const NAVIGATION = [
  { href: '/dashboard', label: 'Home', available: true },
  { href: '/settings/profile', label: 'Settings', available: true },
  { href: '/services', label: 'Services', available: true },
  { href: '/employees', label: 'Employees', available: true },
  { href: '/calendar', label: 'Calendar', available: true },
  { href: '/appointments', label: 'Appointments', available: true },
  { href: '/customers', label: 'Customers', available: true },
  { href: '/conversations', label: 'Conversations', available: true },
  { href: '/analytics', label: 'Analytics', available: true },
];

/**
 * The first path segment — the section a page belongs to.
 *
 * Settings is five pages behind one sidebar entry, so an exact match would leave the sidebar
 * showing nothing selected on four of them. Comparing sections rather than paths is what keeps a
 * link highlighted while the sub-navigation moves within it.
 */
function section(path: string): string {
  return path.split('/')[1] ?? '';
}

export function DashboardShell({ children }: { children: React.ReactNode }) {
  const { session, signOut } = useSession();
  const pathname = usePathname();

  return (
    <div className="flex min-h-dvh flex-col lg:flex-row">
      <aside className="border-border bg-surface border-b lg:w-60 lg:shrink-0 lg:border-r lg:border-b-0">
        <div className="flex items-center justify-between gap-4 px-6 py-4 lg:block">
          <Link
            href="/dashboard"
            className="text-brand text-sm font-semibold tracking-wide uppercase"
          >
            Reception
          </Link>
          <p className="text-ink truncate text-sm font-medium lg:mt-3">{session?.business.name}</p>
        </div>

        <nav className="flex gap-1 overflow-x-auto px-4 pb-3 lg:flex-col lg:overflow-visible lg:pb-6">
          {NAVIGATION.map((item) => {
            const active = section(pathname) === section(item.href);
            if (!item.available) {
              return (
                <span
                  key={item.href}
                  aria-disabled="true"
                  className="text-ink-muted/60 shrink-0 rounded-md px-3 py-2 text-sm whitespace-nowrap"
                >
                  {item.label}
                </span>
              );
            }
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'shrink-0 rounded-md px-3 py-2 text-sm whitespace-nowrap',
                  active
                    ? 'bg-surface-muted text-ink font-medium'
                    : 'text-ink-muted hover:bg-surface-muted',
                )}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-border bg-surface flex items-center justify-between gap-4 border-b px-6 py-3">
          <div className="min-w-0">
            <p className="text-ink truncate text-sm font-medium">{session?.user.fullName}</p>
            <p className="text-ink-muted truncate text-xs">{session?.user.email}</p>
          </div>
          <Button variant="secondary" size="sm" onClick={() => void signOut()}>
            Sign out
          </Button>
        </header>

        <main className="min-w-0 flex-1 px-6 py-8">{children}</main>
      </div>
    </div>
  );
}
