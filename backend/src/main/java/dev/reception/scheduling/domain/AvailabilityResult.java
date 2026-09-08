package dev.reception.scheduling.domain;

import java.util.List;
import java.util.Objects;

/**
 * The answer: one entry per date asked about, and a reason when there is nothing in any of them.
 *
 * <p>Every requested date appears, including the ones with no Slots. A caller rendering a week
 * should not have to reconstruct which dates it asked about in order to draw the empty ones, and a
 * missing date and a date with nothing free would otherwise be indistinguishable.
 *
 * @param emptyReason {@code null} whenever at least one Slot was found — the two fields cannot
 *     disagree, because {@link #of} is the only way to build this
 */
public record AvailabilityResult(List<Slot.Day> days, EmptyReason emptyReason) {

    public AvailabilityResult {
        days = List.copyOf(days);
    }

    /** Sets {@code emptyReason} only when it is warranted, so no caller has to check both fields. */
    public static AvailabilityResult of(List<Slot.Day> days, EmptyReason reasonIfEmpty) {
        boolean anySlots = days.stream().anyMatch(day -> !day.slots().isEmpty());
        return new AvailabilityResult(days, anySlots ? null : Objects.requireNonNull(reasonIfEmpty, "reasonIfEmpty"));
    }

    public boolean isEmpty() {
        return emptyReason != null;
    }
}
