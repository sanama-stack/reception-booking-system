'use client';

import Link from 'next/link';
import { Card, CardHeader, cn } from '@/components/ui';
import type { Onboarding } from '@/lib/business';

interface Step {
  key: keyof Onboarding;
  title: string;
  description: string;
  /** Where the owner goes to do it, or `null` while the screen does not exist yet. */
  href: string | null;
  /** Shown in place of the link, so an unbuilt screen is named rather than silently missing. */
  arrives?: string;
}

/**
 * The five things that decide whether anyone can book, in the order they have to happen.
 *
 * Three of them are answered by `CatalogReadiness`, whose phase-03 implementation truthfully
 * answers "nothing configured" — services and employees arrive in phase 04. They are listed and
 * shown as outstanding rather than hidden: an owner who cannot see the remaining steps cannot tell
 * whether they have finished, and hiding them would also mean rebuilding this list later.
 */
const STEPS: Step[] = [
  {
    key: 'hoursConfigured',
    title: 'Set your opening hours',
    description: 'The week customers can book in. A day with no hours is closed.',
    href: '/settings/hours',
  },
  {
    key: 'hasActiveService',
    title: 'Add a service',
    description: 'What customers book — a haircut, a consultation — with a length and a price.',
    href: null,
    arrives: 'Arrives with services',
  },
  {
    key: 'hasActiveEmployee',
    title: 'Add someone who provides it',
    description: 'The people appointments are booked with.',
    href: null,
    arrives: 'Arrives with staff',
  },
  {
    key: 'hasEmployeeSchedule',
    title: 'Give them a working week',
    description: 'When each person works, which can differ from when the business is open.',
    href: null,
    arrives: 'Arrives with staff',
  },
  {
    key: 'hasBookableService',
    title: 'Connect a service to someone who can do it',
    description: 'A service nobody is assigned to cannot be booked, however well configured it is.',
    href: null,
    arrives: 'Arrives with services',
  },
];

export function OnboardingChecklist({ state }: { state: Onboarding }) {
  const nextIncomplete = STEPS.find((step) => !state[step.key]);
  const done = STEPS.filter((step) => state[step.key]).length;

  return (
    <Card>
      <CardHeader
        title="Getting set up"
        description={
          state.publicPageReady
            ? 'Everything is configured. Your booking page is ready to take appointments.'
            : `${done} of ${STEPS.length} done. Your booking page cannot take appointments until all of them are.`
        }
      />

      <ol className="flex flex-col">
        {STEPS.map((step) => {
          const complete = state[step.key] === true;
          const isNext = step === nextIncomplete;

          return (
            <li
              key={step.key}
              className={cn(
                'border-border flex gap-3 border-b py-3 first:pt-0 last:border-b-0 last:pb-0',
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  'mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full border text-xs',
                  complete
                    ? 'border-success bg-success/10 text-success'
                    : isNext
                      ? 'border-brand text-brand'
                      : 'border-border text-ink-muted',
                )}
              >
                {complete ? '✓' : ''}
              </span>

              <div className="min-w-0 flex-1">
                <p
                  className={cn(
                    'text-sm',
                    complete ? 'text-ink-muted' : 'text-ink font-medium',
                    isNext && 'text-ink font-medium',
                  )}
                >
                  {step.title}
                  <span className="sr-only">{complete ? ' — done' : ' — not done yet'}</span>
                </p>
                <p className="text-ink-muted mt-0.5 text-sm">{step.description}</p>
              </div>

              <div className="shrink-0 self-center text-sm">
                {step.href ? (
                  <Link href={step.href} className="text-brand hover:underline">
                    {complete ? 'Change' : 'Set up'}
                  </Link>
                ) : (
                  <span className="text-ink-muted/70">{step.arrives}</span>
                )}
              </div>
            </li>
          );
        })}
      </ol>

      <div className="border-border mt-4 border-t pt-4">
        <p className="text-ink-muted text-sm">
          {state.publicPageReady ? 'Your booking page' : 'Your booking page will be at'}
        </p>
        <p className="text-ink mt-0.5 font-mono text-sm">{state.bookingUrl}</p>
      </div>
    </Card>
  );
}
