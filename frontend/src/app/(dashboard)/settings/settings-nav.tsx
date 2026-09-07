'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/components/ui';

/**
 * Settings is five screens, which is more than the sidebar should carry — the sidebar lists
 * sections, and this lists the pages inside one.
 *
 * Every entry exists. Unlike the sidebar, which marks phases that have not arrived, there is
 * nothing here to mark: all five ship together in phase 03.
 */
const PAGES = [
  { href: '/settings/profile', label: 'Profile' },
  { href: '/settings/hours', label: 'Opening hours' },
  { href: '/settings/closures', label: 'Closures' },
  { href: '/settings/booking', label: 'Booking' },
  { href: '/settings/faqs', label: 'Receptionist' },
];

export function SettingsNav() {
  const pathname = usePathname();

  return (
    <nav aria-label="Settings" className="border-border -mx-1 flex gap-1 overflow-x-auto border-b">
      {PAGES.map((page) => {
        const active = pathname === page.href;
        return (
          <Link
            key={page.href}
            href={page.href}
            aria-current={active ? 'page' : undefined}
            className={cn(
              'shrink-0 border-b-2 px-3 py-2 text-sm whitespace-nowrap',
              active
                ? 'border-brand text-ink font-medium'
                : 'text-ink-muted hover:text-ink border-transparent',
            )}
          >
            {page.label}
          </Link>
        );
      })}
    </nav>
  );
}
