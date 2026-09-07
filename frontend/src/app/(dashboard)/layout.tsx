import { AuthGuard } from './auth-guard';
import { DashboardShell } from './dashboard-shell';

/**
 * The authenticated surface. Client-rendered behind a guard, per
 * docs/02-product-architecture.md §7 — public pages are server-rendered for first paint and
 * shareability, the dashboard is neither.
 */
export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <AuthGuard>
      <DashboardShell>{children}</DashboardShell>
    </AuthGuard>
  );
}
