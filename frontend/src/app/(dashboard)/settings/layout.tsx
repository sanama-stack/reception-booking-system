import { SettingsNav } from './settings-nav';

/**
 * The settings surface: everything an owner configures about the business itself.
 *
 * It sits inside the dashboard group, so `AuthGuard` and `DashboardShell` already apply — this
 * layout adds only the sub-navigation and the column width.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-ink-muted mt-1 text-sm">
          How your business appears to customers, when you are open, and how bookings work.
        </p>
      </div>
      <SettingsNav />
      {children}
    </div>
  );
}
