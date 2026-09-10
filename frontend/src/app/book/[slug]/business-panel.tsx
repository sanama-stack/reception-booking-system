import { Card } from '@/components/ui';
import { DAYS } from '@/lib/business';
import type { PublicBusiness, PublicDayHours } from '@/lib/public';
import { isoDateWeekday, toBusinessDate } from '@/lib/time';
import { ReceptionistPanel } from './receptionist-panel';

/**
 * The right-hand column: the Receptionist, the opening hours, and the cancellation policy.
 *
 * Everything below the Receptionist is reference material — true regardless of what the visitor
 * has chosen — which is why this stays a server component and only the conversation crosses into
 * the client.
 *
 * It sits *after* the flow in the document, and that is a decision about 360 px rather than about
 * desktop: the columns stack in source order, so a visitor on a phone meets the booking form
 * first. The Receptionist being the default door is a claim about a two-column screen; on a
 * one-column one, putting a chat panel above the form would bury the fallback under the thing it
 * is the fallback for.
 */
export function BusinessPanel({ business, slug }: { business: PublicBusiness; slug: string }) {
  return (
    <aside className="flex flex-col gap-4 lg:sticky lg:top-8">
      {/*
        A server component rendering a client one, which is the whole of the boundary here: the
        hours and the policy are static facts and stay on the server, and only the conversation
        ships JavaScript.
      */}
      {business.aiEnabled && <ReceptionistPanel slug={slug} business={business} />}
      <OpeningHours business={business} />
      <Policy business={business} />
    </aside>
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
