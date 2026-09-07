'use client';

import { Card, CardHeader } from '@/components/ui';
import { useSession } from '@/lib/auth';

/**
 * The empty dashboard an owner lands on after registering.
 *
 * The onboarding checklist that belongs here arrives in phase 03, driven by
 * `GET /business/onboarding` — real configuration state, derived rather than stored. Until then
 * this shows what registration actually created, which is more useful than a placeholder and
 * happens to be the fastest way to see that the session and its tenant resolved correctly.
 */
export default function DashboardHomePage() {
  const { session } = useSession();
  if (!session) return null;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">
          Welcome, {session.user.fullName.split(' ')[0]}
        </h1>
        <p className="text-ink-muted mt-1 text-sm">
          {session.business.name} is set up. Next comes configuring your hours, services and staff.
        </p>
      </div>

      <Card>
        <CardHeader
          title="Your business"
          description="Created when you registered. Everything here becomes editable in the next phase."
        />
        <dl className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-ink-muted">Name</dt>
            <dd className="text-ink mt-0.5 font-medium">{session.business.name}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Public booking page</dt>
            <dd className="text-ink mt-0.5 font-mono text-xs">/book/{session.business.slug}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Timezone</dt>
            <dd className="text-ink mt-0.5 font-medium">{session.business.timezone}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Currency</dt>
            <dd className="text-ink mt-0.5 font-medium">{session.business.currency}</dd>
          </div>
        </dl>
      </Card>

      <Card>
        <CardHeader
          title="Opening hours"
          description="Monday to Friday, 09:00–17:00, created with your account. A day with no hours is closed."
        />
        <p className="text-ink-muted text-sm">
          Editing these arrives with the settings screens in phase 03.
        </p>
      </Card>
    </div>
  );
}
