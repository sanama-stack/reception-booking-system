import { Card } from '@/components/ui';
import { DAYS } from '@/lib/business';
import type { PublicBusiness, PublicDayHours } from '@/lib/public';
import { isoDateWeekday, toBusinessDate } from '@/lib/time';

/**
 * The right-hand column: the Receptionist's reserved space, the opening hours, and the
 * cancellation policy.
 *
 * Everything here is reference material — true regardless of what the visitor has chosen — which
 * is why it is a server component with no state, and why it sits *after* the flow in the document
 * rather than before it. At 360 px the columns stack in source order, so a visitor who came to
 * book meets the booking form first and finds the hours below it.
 */
export function BusinessPanel({ business }: { business: PublicBusiness }) {
  return (
    <aside className="flex flex-col gap-4 lg:sticky lg:top-8">
      <ReceptionistSlot business={business} />
      <OpeningHours business={business} />
      <Policy business={business} />
    </aside>
  );
}

/**
 * Where the Receptionist goes (phase 09).
 *
 * **The space is reserved here, and the reservation is the deliverable**
 * (docs/phases/phase-08-public-booking.md). Laying the column out now is what keeps the Classic
 * Flow a peer of the chat rather than something the chat displaces — and the Classic Flow being a
 * genuine peer is the whole basis of its being the fallback for every AI failure mode
 * (docs/05-ai-architecture.md §8).
 *
 * `aiEnabled` is already read, so phase 09 inherits the branch rather than adding it: a business
 * that has not switched the Receptionist on says nothing about it, because advertising a feature
 * this business does not offer would be worse than an empty column. There is no Settings control
 * for the flag yet — phase 09 owes that too.
 */
function ReceptionistSlot({ business }: { business: PublicBusiness }) {
  if (!business.aiEnabled) return null;

  return (
    <Card className="border-brand/25 bg-brand/[0.03]">
      <h2 className="text-ink text-sm font-semibold">Ask the receptionist</h2>
      <p className="text-ink-muted mt-2 text-sm leading-relaxed">
        {business.name} is setting up a receptionist you can talk to — it will answer questions and
        book for you from this panel. Until then, the form does everything it will.
      </p>
    </Card>
  );
}

/**
 * Seven days, whatever the server sent.
 *
 * A day with no row is closed and there is no closed flag on the wire, so the week is
 * reconstructed rather than mapped — the same reason `WeekEditor.toDraft` does it. And a day can
 * hold **more than one** interval: a business that shuts for lunch sends two rows for Tuesday, and
 * rendering only the first would publish an afternoon-long opening the business does not have.
 */
function OpeningHours({ business }: { business: PublicBusiness }) {
  const today = isoDateWeekday(toBusinessDate(new Date(), business.timezone));

  return (
    <Card>
      <h2 className="text-ink text-sm font-semibold">Opening hours</h2>
      <dl className="mt-3 flex flex-col gap-1.5 text-sm">
        {DAYS.map(({ value, label }) => {
          const intervals = business.hours.filter((entry) => entry.dayOfWeek === value);
          const isToday = value === today;
          return (
            <div key={value} className="flex items-baseline justify-between gap-3">
              <dt className={isToday ? 'text-ink font-medium' : 'text-ink-muted'}>
                {label}
                {isToday && <span className="text-ink-muted font-normal"> · today</span>}
              </dt>
              <dd
                className={
                  intervals.length === 0
                    ? 'text-ink-muted text-right'
                    : 'text-ink text-right tabular-nums'
                }
              >
                {intervals.length === 0 ? 'Closed' : intervals.map(interval).join(', ')}
              </dd>
            </div>
          );
        })}
      </dl>
      <p className="text-ink-muted mt-3 text-xs">
        {/* The zone, on the page rather than assumed. Every time a visitor sees is the business's
            own wall clock, and a customer in another country has to be told which one that is. */}
        Times shown in {business.timezone}.
      </p>
    </Card>
  );
}

/** `09:00 – 13:00`. Already `HH:mm` on the wire, so there is nothing to format. */
function interval(hours: PublicDayHours): string {
  return `${hours.opensAt} – ${hours.closesAt}`;
}

/**
 * What happens if you cancel late, in the business's own words where they wrote any.
 *
 * The window is stated whether or not there is policy text, because the number is the part that is
 * enforced: the Cancellation Window is what the server checks, and the prose is what the business
 * would like understood about it. A page showing only the prose would be showing the part nobody
 * is bound by.
 */
function Policy({ business }: { business: PublicBusiness }) {
  const { cancellationWindowHours: hours, cancellationPolicy } = business;

  return (
    <Card>
      <h2 className="text-ink text-sm font-semibold">Changes and cancellations</h2>
      <p className="text-ink-muted mt-2 text-sm leading-relaxed">
        {hours === 0
          ? 'You can cancel or move your appointment yourself at any time before it starts.'
          : `You can cancel or move your appointment yourself up to ${hours === 1 ? 'an hour' : `${hours} hours`} before it starts.`}
      </p>
      {cancellationPolicy && (
        <p className="text-ink-muted mt-2 text-sm leading-relaxed whitespace-pre-line">
          {cancellationPolicy}
        </p>
      )}
      {(business.phone || business.email) && (
        <p className="text-ink-muted mt-3 text-sm">
          After that, contact {business.name}
          {business.phone && (
            <>
              {' on '}
              <a className="text-brand hover:underline" href={`tel:${business.phone}`}>
                {business.phone}
              </a>
            </>
          )}
          {business.email && (
            <>
              {business.phone ? ' or ' : ' at '}
              <a className="text-brand hover:underline" href={`mailto:${business.email}`}>
                {business.email}
              </a>
            </>
          )}
          .
        </p>
      )}
    </Card>
  );
}
