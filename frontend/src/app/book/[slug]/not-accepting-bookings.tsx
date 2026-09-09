import { Card } from '@/components/ui';
import type { PublicBusiness } from '@/lib/public';

/**
 * The business exists, and has nothing to offer.
 *
 * `GET …/services` returns *active* Services only, so an empty list means either that no Service
 * has been created yet or that every one of them has been switched off. Both are the same fact
 * from the visitor's side, and neither is an error: this is a real page for a real business that
 * is not taking bookings here at the moment.
 *
 * **An explanatory page rather than an empty grid**
 * (docs/phases/phase-08-public-booking.md). A form offering nothing to choose looks broken and
 * invites a visitor to reload it; a sentence saying so, next to a phone number, is the thing that
 * gets them their appointment. Which is why this is not an `EmptyState`: the interesting content
 * here is the way to reach a human, not the absence of a list.
 */
export function NotAcceptingBookings({ business }: { business: PublicBusiness }) {
  const contactable = Boolean(business.phone || business.email);

  return (
    <Card>
      <h2 className="text-ink text-base font-semibold">Not taking online bookings at the moment</h2>
      <p className="text-ink-muted mt-2 text-sm leading-relaxed">
        {business.name} has no services listed for booking right now. This page will work again as
        soon as they do — nothing is wrong with your link.
      </p>

      {contactable ? (
        <div className="border-border mt-5 border-t pt-5">
          <p className="text-ink text-sm font-medium">Contact them directly</p>
          <ul className="mt-2 flex flex-col gap-1 text-sm">
            {business.phone && (
              <li>
                <a className="text-brand hover:underline" href={`tel:${business.phone}`}>
                  {business.phone}
                </a>
              </li>
            )}
            {business.email && (
              <li>
                <a className="text-brand hover:underline" href={`mailto:${business.email}`}>
                  {business.email}
                </a>
              </li>
            )}
          </ul>
        </div>
      ) : (
        // Nothing to offer and no way to get in touch. Saying so is better than a section header
        // over an empty list, which would read as a page that failed to load its own content.
        <p className="text-ink-muted mt-4 text-sm">
          They have not published a phone number or an email address here either.
        </p>
      )}
    </Card>
  );
}
