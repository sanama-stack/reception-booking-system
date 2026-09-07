import { Card } from '@/components/ui/card';

/**
 * Placeholder landing page. The marketing surface arrives with the dashboard in later phases;
 * this exists so `make up` has something honest to show at the single origin.
 */
export default function LandingPage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-2xl flex-col justify-center gap-6 px-6 py-16">
      <div>
        <p className="text-brand text-sm font-medium tracking-wide uppercase">Reception</p>
        <h1 className="text-ink mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">
          Appointment booking with an AI receptionist
        </h1>
        <p className="text-ink-muted mt-4 text-base leading-relaxed">
          Businesses configure their services, staff and hours. Their customers book by talking to a
          receptionist that can only act through a booking engine it cannot bypass.
        </p>
      </div>

      <Card>
        <h2 className="text-ink text-sm font-semibold">Phase 01 — Foundation</h2>
        <p className="text-ink-muted mt-2 text-sm leading-relaxed">
          The full topology is running behind a single origin. Everything below is served from
          <code className="bg-surface-muted mx-1 rounded px-1.5 py-0.5 text-xs">
            localhost:8080
          </code>
          — which is what lets authentication use httpOnly cookies with no CORS anywhere.
        </p>
        <ul className="text-ink-muted mt-4 space-y-1.5 text-sm">
          <li>
            <a className="text-brand hover:underline" href="/api/health">
              /api/health
            </a>{' '}
            — database and mail connectivity
          </li>
          <li>
            <a className="text-brand hover:underline" href="/api/docs">
              /api/docs
            </a>{' '}
            — API description
          </li>
          <li>
            <a className="text-brand hover:underline" href="http://localhost:8025">
              localhost:8025
            </a>{' '}
            — Mailpit
          </li>
        </ul>
      </Card>
    </main>
  );
}
