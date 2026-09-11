'use client';

import Link from 'next/link';
import { Card, CardHeader, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import type { Onboarding } from '@/lib/business';
import { OnboardingChecklist } from './onboarding-checklist';
import { QuickStats } from './quick-stats';
import { TodaysAgenda } from './todays-agenda';

/**
 * Where an owner lands, and what they should do next.
 *
 * The checklist is read from `/business/onboarding` on every visit rather than held anywhere: it
 * is derived server-side from real configuration, so it is right the moment after a change is made
 * somewhere else, including in another tab.
 *
 * **Three reads, each with its own gate.** The agenda, the stats and the checklist answer three
 * unrelated questions, and one of them failing should cost the owner that panel rather than the
 * screen — a home page that goes blank because a summary query timed out is worse than a home page
 * with a summary that says so. They are separate components for that reason, not for tidiness.
 */
export default function DashboardHomePage() {
  const { session } = useSession();
  const onboarding = useResource<Onboarding>('/business/onboarding');

  if (!session) return null;

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6">
      <div>
        <h1 className="text-ink text-2xl font-semibold tracking-tight">
          Welcome, {session.user.fullName.split(' ')[0]}
        </h1>
        <p className="text-ink-muted mt-1 text-sm">
          {session.business.name} — everything about the business is under{' '}
          <Link href="/settings/profile" className="text-brand hover:underline">
            Settings
          </Link>
          .
        </p>
      </div>

      <QuickStats timezone={session.business.timezone} />

      <TodaysAgenda timezone={session.business.timezone} />

      <ResourceGate resource={onboarding}>
        {(state) => <OnboardingChecklist state={state} />}
      </ResourceGate>

      <Card>
        <CardHeader title="Your business" description="Change any of this under Settings." />
        <dl className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-ink-muted">Name</dt>
            <dd className="text-ink mt-0.5 font-medium">{session.business.name}</dd>
          </div>
          <div>
            <dt className="text-ink-muted">Booking page address</dt>
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
    </div>
  );
}
