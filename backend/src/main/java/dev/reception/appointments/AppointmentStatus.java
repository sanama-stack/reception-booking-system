package dev.reception.appointments;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where an Appointment is in its life, and which moves are legal from there.
 *
 * <p><strong>An explicit table, not scattered {@code if}s.</strong> The rule is small enough to
 * inline at each of the three call sites and that is exactly the problem: three copies drift, and
 * the drift shows up as an appointment that can be completed twice or cancelled after it was marked
 * a no-show. Written once, the whole state machine is legible in eight lines and the tests can
 * enumerate it.
 *
 * <p>Terminal states are terminal. There is no path back to {@code CONFIRMED} — undoing a
 * cancellation would have to re-acquire the time, which another booking may already hold, and
 * "cancel then book again" is the honest way to express that.
 */
public enum AppointmentStatus {

    /** Booked and holding the Employee's time. The only status the exclusion constraint enforces. */
    CONFIRMED,

    /** The Customer attended. */
    COMPLETED,

    /** The Customer did not attend. Distinct from {@code CANCELLED}: nobody told anyone. */
    NO_SHOW,

    /** Called off, by either side. The time is released the instant this is written. */
    CANCELLED;

    private static final Map<AppointmentStatus, Set<AppointmentStatus>> ALLOWED = Map.of(
            CONFIRMED, EnumSet.of(COMPLETED, NO_SHOW, CANCELLED),
            COMPLETED, EnumSet.noneOf(AppointmentStatus.class),
            NO_SHOW, EnumSet.noneOf(AppointmentStatus.class),
            CANCELLED, EnumSet.noneOf(AppointmentStatus.class));

    /**
     * Whether this is a legal move.
     *
     * <p>A transition to the status already held is <em>not</em> legal here. Cancelling twice is
     * idempotent, but that is a property of the cancel operation rather than of the state machine —
     * {@code CancellationService} answers a repeat call before consulting this, so the two do not
     * have to agree about what "already cancelled" means (docs/04-api-overview.md §2).
     */
    public boolean canMoveTo(AppointmentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Whether the Employee's time is held. The predicate the exclusion constraint is built on. */
    public boolean holdsTime() {
        return this == CONFIRMED;
    }
}
