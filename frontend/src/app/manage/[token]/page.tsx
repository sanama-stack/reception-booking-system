import type { Metadata } from 'next';
import { ErrorState } from '@/components/ui';
import { ApiError } from '@/lib/api/client';
import { publicApi } from '@/lib/public';
import { toBusinessDate } from '@/lib/time';
import { LinkExpired } from './link-expired';
import { ManageFlow } from './manage-flow';

/**
 * One appointment, opened from the link in its own confirmation email.
 *
 * **The URL is the credential.** `/manage/{token}` carries a signed capability issued by phase 07,
 * and that shapes three things on this page: the metadata below suppresses the referrer so the
 * token cannot ride out to a third party, nothing here renders an outbound link that could carry
 * it anyway, and there is no sign-in prompt — the reader is a Customer who has no account and
 * never will (CONTEXT.md).
 *
 * **Resolved on the server**, like the booking page's header, and for a sharper reason: the whole
 * page *is* the appointment. Fetching it in the browser would mean a spinner where the summary
 * goes and, for a link that has expired, a spinner that turns into a refusal — which reads as a
 * failure rather than as an answer. The resolved appointment is handed to a client component as
 * its opening state, and every later change comes back from the write that made it.
 *
 * One column, not the booking page's two. The chat column there is a reservation for a
 * Receptionist that helps a stranger *choose*; a Customer who already has an appointment and wants
 * to move or cancel it has one short question, and a second column would be an empty promise
 * beside it. Phase 09 can take the space if it turns out to want it.
 */

/** Availability and a cancellation deadline both move while nobody is looking. Never cached. */
export const dynamic = 'force-dynamic';

interface PageProps {
  params: Promise<{ token: string }>;
}

/**
 * Static, and deliberately says nothing about the appointment.
 *
 * A title naming the business or the service would put it in the browser's history, in a tab
 * title read over a shoulder, and in whatever the operating system does with recently-visited
 * pages — for a URL that is already a bearer token. `referrer: no-referrer` is the load-bearing
 * line: without it, any navigation away from this page hands the token to wherever it went.
 */
export const metadata: Metadata = {
  title: 'Your appointment',
  robots: { index: false, follow: false },
  referrer: 'no-referrer',
};

export default async function ManagePage({ params }: PageProps) {
  const { token } = await params;

  let appointment;
  try {
    appointment = await publicApi.manage(token);
  } catch (cause) {
    if (!(cause instanceof ApiError)) throw cause;

    // Every way a token can fail to authorise arrives as this one code — expired, tampered with,
    // truncated by an email client that wrapped the line, or for an appointment that no longer
    // exists. The server is deliberately unable to tell them apart, so neither is this page, and
    // the copy is written for the case that is overwhelmingly the most common.
    if (cause.code === 'MANAGE_TOKEN_INVALID') return <LinkExpired />;

    // Anything else is a fault at our end rather than a bad link, and saying "this link is no
    // longer valid" for a backend that is briefly down would send the Customer looking for a new
    // email that nobody is going to send them.
    return (
      <main className="mx-auto flex min-h-dvh max-w-lg flex-col justify-center px-4 py-16">
        <ErrorState error={cause} />
        <p className="text-ink-muted mt-4 text-sm">
          This is a problem at our end, not with your link — it is still good. Trying again in a few
          minutes is worth more than reloading now.
        </p>
      </main>
    );
  }

  // Today in the *business's* timezone, resolved from the server's clock, so the reschedule grid
  // is anchored the same way the booking page's is and no browser clock is ever consulted.
  const today = toBusinessDate(new Date(), appointment.timezone);

  return (
    <main className="mx-auto w-full max-w-2xl px-4 py-8 sm:px-6 sm:py-12">
      <header className="mb-6">
        <p className="text-ink-muted text-sm font-medium tracking-wide uppercase">
          Your appointment
        </p>
        <h1 className="text-ink mt-1 text-2xl font-semibold tracking-tight sm:text-3xl">
          {appointment.business.name}
        </h1>
      </header>

      <ManageFlow token={token} initial={appointment} today={today} />
    </main>
  );
}
