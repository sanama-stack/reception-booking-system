package dev.reception.appointments;

import dev.reception.business.BusinessService;
import dev.reception.common.error.ApiException;
import dev.reception.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The period immediately before an Appointment in which a Customer may no longer change it
 * themselves.
 *
 * <p>Its own class because two paths ask the same question. CONTEXT.md defines the window as the
 * period a Customer may no longer "cancel <em>or reschedule</em>" — two copies of that arithmetic
 * would be two chances for cancelling and rescheduling to disagree by an hour, and the disagreement
 * would only ever be visible to a customer at the moment they are already frustrated.
 *
 * <p><strong>The Business is never bound by it</strong> (CONTEXT.md). Callers ask only for a
 * Customer; there is deliberately no parameter here that could be used to bind one.
 */
@Component
public class CancellationWindow {

    private final BusinessService businesses;
    private final Clock clock;

    public CancellationWindow(BusinessService businesses, Clock clock) {
        this.businesses = businesses;
        this.clock = clock;
    }

    /**
     * @throws ApiException {@code CANCELLATION_WINDOW_CLOSED} when the window has shut
     */
    public void requireOpenFor(Appointment appointment) {
        Instant closesAt =
                appointment.startsAt().minus(Duration.ofHours(businesses.read().cancellationWindowHours()));
        // Not isAfter: at exactly the boundary the window is shut. A customer who is told they have
        // until 24 hours before has until then, not through it.
        if (!clock.instant().isBefore(closesAt)) {
            throw new ApiException(
                    ErrorCode.CANCELLATION_WINDOW_CLOSED,
                    "This appointment can no longer be changed online. Please contact the business.");
        }
    }
}
