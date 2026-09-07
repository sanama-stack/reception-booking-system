'use client';

import Link from 'next/link';
import { Card, CardHeader, ResourceGate } from '@/components/ui';
import { useResource } from '@/lib/api/use-resource';
import { useSession } from '@/lib/auth';
import type { Onboarding } from '@/lib/business';
import { OnboardingChecklist } from './onboarding-checklist';

/**
 * Where an owner lands, and what they should do next.
 *
 * The checklist is read from `/business/onboarding` on every visit rather than held anywhere: it
 * is derived server-side from real configuration, so it is right the moment after a change is made
 * somewhere else, including in another tab.
 */
export default function DashboardHomePage() {
  const { session } = useSession();
  const onboarding = useResource<Onboarding>('/business/onboarding');

  if (!session) return null;

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
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
