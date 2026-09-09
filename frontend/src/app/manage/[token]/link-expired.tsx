import { ButtonLink, Card } from '@/components/ui';

/**
 * The link did not authorise anything.
 *
 * `MANAGE_TOKEN_INVALID` is one code for every way a token can fail — expired, tampered with,
 * signed with a different secret, or naming an appointment that is gone. The server refuses to
 * distinguish them, because a message that separated "expired" from "no such appointment" would
 * confirm to somebody guessing tokens that a guess had named a real one. So this page cannot say
 * which happened either, and it does not pretend to.
 *
 * **It leads with expiry** because that is what almost always happened: a Manage Link is issued to
 * last until the appointment ends, so a link that stops working is nearly always one being used
 * afterwards, out of an old email.
 *
 * **There is no way back in from here, and the page says so plainly.** The other proof the API
 * accepts — Confirmation Code plus phone number — has no page of its own yet, so the honest
 * remedy is the business's own contact details, which this page does not have: nothing has been
 * authorised, so nothing about any business has been resolved. Telling the reader to use the
 * details in their confirmation email is the one instruction that is certainly actionable.
 */
export function LinkExpired() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-lg flex-col justify-center px-4 py-16">
      <Card>
        <p className="text-ink-muted text-sm font-medium tracking-wide uppercase">Link expired</p>
        <h1 className="text-ink mt-2 text-2xl font-semibold tracking-tight">
          This link no longer opens your appointment
        </h1>
        <p className="text-ink-muted mt-3 text-sm leading-relaxed">
          Links in confirmation emails stop working once the appointment has been and gone. If yours
          is still ahead of you, the most likely explanation is that the link was cut short
          somewhere between the email and the address bar — some mail apps wrap long ones across two
          lines.
        </p>
        <p className="text-ink-muted mt-3 text-sm leading-relaxed">
          Opening the original email again and following the link from there is the quickest thing
          to try. Failing that, the business’s phone number and email address are in that same
          email, and your confirmation code is at the top of it — quote it and they can find the
          booking straight away.
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
