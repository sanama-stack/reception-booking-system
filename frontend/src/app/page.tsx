import Link from 'next/link';
import { Button, Card } from '@/components/ui';

/**
 * Placeholder landing page. The marketing surface arrives in a later phase; this exists so
 * `make up` has something honest to show at the single origin, and so there is a way into the
 * product from it.
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

      <div className="flex flex-wrap gap-3">
        <Link href="/register">
          <Button>Create your business</Button>
        </Link>
        <Link href="/login">
          <Button variant="secondary">Sign in</Button>
        </Link>
      </div>

      <Card>
        <h2 className="text-ink text-sm font-semibold">Phase 02 — Authentication and tenancy</h2>
        <p className="text-ink-muted mt-2 text-sm leading-relaxed">
          Everything is served from one origin, which is what lets authentication use httpOnly
          cookies with no CORS anywhere — and no token handling in this application at all.
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
            <a className="text-brand hover:underline" href="http://localhost:9083">
              localhost:9083
            </a>{' '}
            — Mailpit
          </li>
        </ul>
      </Card>
    </main>
  );
}
