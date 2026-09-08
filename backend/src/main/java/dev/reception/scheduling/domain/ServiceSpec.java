package dev.reception.scheduling.domain;

import java.time.Duration;
import java.util.UUID;

/**
 * What the engine needs to know about the Service being booked, and nothing else.
 *
 * <p>Not the {@code Service} entity: the engine is pure and must not depend on persistence
 * (enforced by {@code LayeringTest}), and a price or a name could not change a Slot's correctness.
 *
 * @param bufferBeforeMinutes padding that blocks the Employee before the Appointment starts
 * @param bufferAfterMinutes padding that blocks the Employee after it ends
 */
public record ServiceSpec(UUID serviceId, int durationMinutes, int bufferBeforeMinutes, int bufferAfterMinutes) {

    public Duration duration() {
        return Duration.ofMinutes(durationMinutes);
    }

    /**
     * The span the Employee is actually occupied for — the Appointment plus both Buffers.
     *
     * <p>This is what phase 06 stores as {@code blocked_from}/{@code blocked_to} and what the
     * exclusion constraint compares, so the engine and the constraint are asking the same question
     * of the same interval (docs/03-data-model.md §4).
     */
    public TimeRange occupancyFor(TimeRange appointment) {
        return new TimeRange(
                appointment.start().minus(Duration.ofMinutes(bufferBeforeMinutes)),
                appointment.end().plus(Duration.ofMinutes(bufferAfterMinutes)));
    }
}
