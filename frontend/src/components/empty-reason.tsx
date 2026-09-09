import { ButtonLink } from '@/components/ui';
import { formatDuration } from '@/lib/catalog';
import type { EmptyReason } from '@/lib/scheduling';

/**
 * An `emptyReason` in words, pointed at the setting that would change it.
 *
 * This is the whole point of `emptyReason` existing: an empty slot list tells an owner nothing, and
 * the four reasons send them to four different places. The engine decides between them in the order
 * they are declared and each makes the next moot, so exactly one ever arrives.
 *
 * **Written once, for two screens.** Phase 05's availability preview asked about one named person;
 * phase 06's booking flow asks on behalf of a customer and may name nobody. Those need different
 * sentences but the same mapping, and two copies of a switch over a closed enum is two `default`
 * branches for a fifth reason to land in silently — which is exactly what would go unnoticed. The
 * copy varies inside each branch; the branches themselves exist once.
 */
export interface Explanation {
  title: string;
  description: string;
  action?: React.ReactNode;
}

export interface EmptyReasonContext {
  serviceName: string;
  durationMinutes: number;
  /**
   * The Employee the question was asked about, or `null` for "anyone who can do this".
   *
   * `null` is not an unknown name — it is a different question, and it changes which advice is
   * true. Telling an owner to check one person's schedule when they asked about the whole team
   * would send them to the wrong screen.
   */
  employeeName: string | null;
}

export function explainEmptyReason(
  reason: EmptyReason | null,
  context: EmptyReasonContext,
): Explanation {
  const { serviceName, durationMinutes, employeeName } = context;
  const duration = formatDuration(durationMinutes);

  switch (reason) {
    case 'NO_ELIGIBLE_EMPLOYEE':
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
      return {
        title: 'Every time that would fit is taken',
        description:
          'Time off, a closure or an existing appointment covers each one. Removing any of them frees the times it covered — and a booking removes more starts than it occupies, because every start that would overlap it goes too.',
      };
    default:
      // The contract says a reason accompanies every empty result. If one is missing, say so
      // plainly rather than inventing the most likely explanation.
      return {
        title: 'No times available',
        description:
          'The engine returned no times and gave no reason, which it is meant to do whenever a result is empty.',
      };
  }
}
