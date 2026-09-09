import { ButtonLink, Card } from '@/components/ui';

/**
 * There is no booking page at this address.
 *
 * Reached from `notFound()` when the slug filter answers `404` — which it does before any other
 * work happens, so this is the whole of what the application knows: the address is wrong.
 *
 * **It says "we could not find it", not "it does not exist"**, and the difference is deliberate.
 * A slug can also 404 because the business changed it, so a page announcing non-existence would
 * be confidently wrong for the most likely case. It offers the two things that actually help — go
 * back to whoever sent the link, or check the address — rather than a search box this application
 * has nothing to put behind.
 *
 * No sign-in prompt either. The person reading this is almost certainly a Customer who has no
 * account and never will (CONTEXT.md), and offering one would be an answer to a question they did
 * not ask.
 */
export default function BookingPageNotFound() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-lg flex-col justify-center px-4 py-16">
      <Card>
        <p className="text-ink-muted text-sm font-medium tracking-wide uppercase">Not found</p>
        <h1 className="text-ink mt-2 text-2xl font-semibold tracking-tight">
          There is no booking page at this address
        </h1>
        <p className="text-ink-muted mt-3 text-sm leading-relaxed">
          The link may have a typo in it, or the business may have changed its address since it was
          sent to you. Checking it against the original — an email, a message, their website — is
          the quickest way to tell which.
        </p>
        <p className="text-ink-muted mt-3 text-sm leading-relaxed">
          If you already have an appointment and only need to change it, the confirmation email you
          were sent carries a link straight to it.
        </p>
        <div className="mt-5">
          <ButtonLink href="/" variant="secondary">
            Reception
          </ButtonLink>
        </div>
      </Card>
    </main>
  );
}
