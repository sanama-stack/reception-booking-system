import { ButtonLink } from '@/components/ui';
import { formatDuration } from '@/lib/catalog';
import type { EmptyReason } from '@/lib/scheduling';

/**
 * An `emptyReason` in words, for whoever is reading it.
 *
 * This is the whole point of `emptyReason` existing: an empty slot list tells nobody anything, and
 * the four reasons mean four different things. The engine decides between them in the order they
 * are declared and each makes the next moot, so exactly one ever arrives.
 *
 * **Written once, for three screens and two audiences.** Phase 05's availability preview asks
 * about one named person; phase 06's booking flow asks on behalf of a Customer and may name
 * nobody; phase 08's public page is read *by* the Customer. Those need different sentences but the
 * same mapping, and two copies of a switch over a closed enum is two `default` branches for a
 * fifth reason to land in silently — which is exactly what would go unnoticed. The copy varies
 * inside each branch; the branches themselves exist once.
 */
export interface Explanation {
  title: string;
  description: string;
  action?: React.ReactNode;
}

/**
 * Who is being told, and it changes more than the tone.
 *
 * An owner is told which setting would change the answer and given a link to it. A Customer is
 * told what to try instead — they cannot change any of these things, and a booking page that sent
 * a stranger to `/settings/hours` would be offering them a door they cannot open. Every `action`
 * below is therefore owner-only, and that is a rule about the audience rather than about the
 * reason.
 *
 * The two also ask different questions. An owner asks about one date; a Customer is shown a
 * fortnight at once, so "that day" is wrong for them and "in the next two weeks" is wrong for the
 * owner. The noun differs per audience, which is the second reason this is not just a tone flag.
 */
export type Audience = 'owner' | 'customer';

export interface EmptyReasonContext {
  audience: Audience;
  serviceName: string;
  durationMinutes: number;
  /**
   * The Employee the question was asked about, or `null` for "anyone who can do this".
   *
   * `null` is not an unknown name — it is a different question, and it changes which advice is
   * true. Telling an owner to check one person's schedule when they asked about the whole team
   * would send them to the wrong screen; telling a Customer to try somebody else when they never
   * named anybody would be advice they cannot act on.
   */
  employeeName: string | null;
}

export function explainEmptyReason(
  reason: EmptyReason | null,
  context: EmptyReasonContext,
): Explanation {
  const { audience, serviceName, durationMinutes, employeeName } = context;
  const duration = formatDuration(durationMinutes);
  const customer = audience === 'customer';

  switch (reason) {
    case 'NO_ELIGIBLE_EMPLOYEE':
      if (customer) {
        return employeeName
          ? {
              title: `${employeeName} cannot be booked for this`,
              description: `${employeeName} does not currently provide ${serviceName}. Try “Anyone available”, or another service.`,
            }
          : {
              title: 'This service cannot be booked online',
              description: `Nobody is set up to provide ${serviceName} at the moment. Another service may be available, or you can contact the business directly.`,
            };
      }
      return employeeName
        ? {
            title: 'Nobody can perform this service',
            description: `${employeeName} would have to be active and assigned to ${serviceName} for it to be bookable with them.`,
          }
        : {
            title: 'Nobody can perform this service',
            description: `${serviceName} needs at least one active employee assigned to it before anything can be booked for it.`,
            action: (
              <ButtonLink href="/employees" variant="secondary" size="sm">
                Employees
              </ButtonLink>
            ),
          };
    case 'OUTSIDE_HORIZON':
      if (customer) {
        return {
          // Both directions, because one reason covers both bounds of the Booking Horizon: a
          // window inside the minimum lead time and one beyond the maximum advance arrive as the
          // same `OUTSIDE_HORIZON`, and there is nothing in the response to tell them apart.
          // Advising only "further out" is therefore wrong half the time — and was, until a window
          // paged past the sixty-day limit was watched saying it in a browser.
          title: 'Those dates are outside the booking window',
          description:
            'This business takes bookings within a set window — not too far ahead, and not in the final hours before one starts. Try dates nearer to today, or a little further out.',
        };
      }
      return {
        title: 'That date is outside your booking window',
        description:
          'Bookings close a set time before they start and open a set number of days ahead. Today’s remaining times can fall inside that lead time too.',
        action: (
          <ButtonLink href="/settings/booking" variant="secondary" size="sm">
            Booking settings
          </ButtonLink>
        ),
      };
    case 'CLOSED':
      if (customer) {
        return {
          title: 'No times in this period',
          description: employeeName
            ? `Either the business is closed, ${employeeName} is not working, or the two only overlap for less than the ${duration} ${serviceName} needs. Try a later date.`
            : `Either the business is closed on these dates, nobody who provides ${serviceName} is working, or the open time is shorter than the ${duration} it needs. Try a later date.`,
        };
      }
      return {
        title: 'Nothing that day could hold this appointment',
        description: employeeName
          ? `Either you are closed, ${employeeName} is not working, or the two only overlap for less than the ${duration} ${serviceName} needs.`
          : `Either you are closed that day, nobody who provides ${serviceName} is working, or the overlap is shorter than the ${duration} it needs.`,
        action: (
          <ButtonLink href="/settings/hours" variant="secondary" size="sm">
            Opening hours
          </ButtonLink>
        ),
      };
    case 'FULLY_BOOKED':
      if (customer) {
        return {
          title: 'Fully booked',
          description:
            'Every time that would fit is already taken. This is the one reason that changes on its own — a cancellation frees the times it covered, so it is worth looking again, or trying dates further out.',
        };
      }
      return {
        title: 'Every time that would fit is taken',
        description:
          'Time off, a closure or an existing appointment covers each one. Removing any of them frees the times it covered — and a booking removes more starts than it occupies, because every start that would overlap it goes too.',
      };
    default:
      // The contract says a reason accompanies every empty result. If one is missing, say so
      // plainly rather than inventing the most likely explanation — and to a Customer, say the
      // part they can act on, because the contract is not their problem.
      return customer
        ? {
            title: 'No times available',
            description:
              'Nothing came back for these dates, and no reason was given. Try a different date or service, or contact the business directly.',
          }
        : {
            title: 'No times available',
            description:
              'The engine returned no times and gave no reason, which it is meant to do whenever a result is empty.',
          };
  }
}
