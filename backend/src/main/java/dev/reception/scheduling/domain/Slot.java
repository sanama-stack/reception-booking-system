package dev.reception.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A start time availability has proven bookable, and the Employee who would perform it.
 *
 * <p>A Slot is computed, never stored (CONTEXT.md). It carries its Employee even when none was
 * requested, so booking never has to resolve one — resolving later would mean the Employee chosen at
 * display time and the one written at booking time could differ, which is a race the exclusion
 * constraint cannot see.
 */
public record Slot(Instant startsAt, Instant endsAt, UUID employeeId, String employeeName) {

    public Slot {
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(employeeId, "employeeId");
        Objects.requireNonNull(employeeName, "employeeName");
    }

    /** The Slots on one calendar date in the Business timezone, in ascending order. */
    public record Day(LocalDate date, List<Slot> slots) {

        public Day {
            Objects.requireNonNull(date, "date");
            slots = List.copyOf(slots);
        }
    }
}
