package dev.reception.scheduling.domain;

import static dev.reception.scheduling.domain.AvailabilityFixture.LIKA;
import static dev.reception.scheduling.domain.AvailabilityFixture.MONDAY;
import static dev.reception.scheduling.domain.AvailabilityFixture.NOW;
import static dev.reception.scheduling.domain.AvailabilityFixture.TBILISI;
import static dev.reception.scheduling.domain.AvailabilityFixture.at;
import static dev.reception.scheduling.domain.AvailabilityFixture.busy;
import static dev.reception.scheduling.domain.AvailabilityFixture.clockAt;
import static dev.reception.scheduling.domain.AvailabilityFixture.clockAtLocal;
import static dev.reception.scheduling.domain.AvailabilityFixture.config;
import static dev.reception.scheduling.domain.AvailabilityFixture.defaultConfig;
import static dev.reception.scheduling.domain.AvailabilityFixture.employee;
import static dev.reception.scheduling.domain.AvailabilityFixture.lika;
import static dev.reception.scheduling.domain.AvailabilityFixture.on;
import static dev.reception.scheduling.domain.AvailabilityFixture.service;
import static dev.reception.scheduling.domain.AvailabilityFixture.weekdays;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code isSlotBookable} — the single-slot check phase 06 runs before it writes.
 *
 * <p>Its job is not to prevent a double booking; the exclusion constraint does that (ADR-0002). Its
 * job is to turn every <em>other</em> way a booking can be wrong into a sentence naming the cause,
 * so a Customer is told the shop shuts at five rather than being handed a constraint violation.
 *
 * <p>The last case here is the one that matters most: every Slot {@code findSlots} offers must pass
 * this check. Two entry points into one set of rules is exactly where a system starts contradicting
 * itself, and phase 06 is where that contradiction would surface as a booking refused seconds after
 * being offered.
 */
class SlotBookabilityTest {

    private final AvailabilityEngine engine = new AvailabilityEngine();

    private Optional<UnbookableReason> check(
            String localTime, ServiceSpec spec, BusinessSchedulingConfig config, EmployeeAvailabilityInput who, Clock clock) {
        return engine.isSlotBookable(at(MONDAY, localTime, TBILISI), spec, config, who, clock);
    }

    private Optional<UnbookableReason> check(String localTime) {
        return check(localTime, service(60), defaultConfig(), lika(), clockAt(NOW));
    }

    @Test
    @DisplayName("an ordinary slot in the middle of the day is bookable")
    void an_ordinary_slot() {
        assertThat(check("10:00")).isEmpty();
    }

    @Test
    @DisplayName("the last slot of the day is bookable even with a buffer that runs past closing")
    void the_last_slot_with_a_trailing_buffer() {
        assertThat(check("16:00", service(60, 0, 30), defaultConfig(), lika(), clockAt(NOW)))
                .isEmpty();
    }

    @Test
    @DisplayName("a start in the past is refused as past, not as too soon")
    void in_the_past() {
        assertThat(check("10:00", service(60), defaultConfig(), lika(), clockAtLocal("2026-09-14T11:00:00", TBILISI)))
                .contains(UnbookableReason.BOOKING_IN_PAST);
    }

    @Test
    @DisplayName("a start inside the lead time is refused as too soon")
    void inside_the_lead_time() {
        BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 120, 60);

        assertThat(check("10:00", service(60), config, lika(), clockAtLocal("2026-09-14T09:00:00", TBILISI)))
                .contains(UnbookableReason.BELOW_MIN_LEAD_TIME);
        assertThat(check("11:00", service(60), config, lika(), clockAtLocal("2026-09-14T09:00:00", TBILISI)))
                .isEmpty();
    }

    @Test
    @DisplayName("a start beyond the maximum advance is refused")
    void beyond_the_maximum_advance() {
        BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 0, 7);

        assertThat(engine.isSlotBookable(
                        at(MONDAY.plusDays(7), "10:00", TBILISI),
                        service(60),
                        config,
                        lika(),
                        clockAtLocal("2026-09-14T09:00:00", TBILISI)))
                .contains(UnbookableReason.BEYOND_MAX_ADVANCE);
    }

    @Test
    @DisplayName("a start between two grid positions is refused")
    void off_the_grid() {
        assertThat(check("10:07")).contains(UnbookableReason.NOT_ON_SLOT_GRID);
    }

    @Test
    @DisplayName("outside the business hours is named as such, even when the employee is willing")
    void outside_business_hours() {
        assertThat(check("08:00", service(60), defaultConfig(), employee(LIKA, "Lika", weekdays("07:00", "17:00")), clockAt(NOW)))
                .contains(UnbookableReason.OUTSIDE_BUSINESS_HOURS);
    }

    @Test
    @DisplayName("inside the business hours but outside this employee's schedule is a different answer")
    void outside_working_hours() {
        // The distinction is the point: the first means try another time, the second means try
        // another person.
        assertThat(check("09:00", service(60), defaultConfig(), employee(LIKA, "Lika", weekdays("13:00", "17:00")), clockAt(NOW)))
                .contains(UnbookableReason.OUTSIDE_WORKING_HOURS);
    }

    @Test
    @DisplayName("a service running past closing is outside business hours, not merely unavailable")
    void running_past_closing() {
        assertThat(check("16:30")).contains(UnbookableReason.OUTSIDE_BUSINESS_HOURS);
    }

    @Test
    @DisplayName("an existing appointment takes the slot")
    void taken_by_an_appointment() {
        EmployeeAvailabilityInput busyLika = employee(
                LIKA, "Lika", weekdays("09:00", "17:00"), List.of(), List.of(busy(MONDAY, "10:00", "11:00", TBILISI)));

        assertThat(check("10:00", service(60), defaultConfig(), busyLika, clockAt(NOW)))
                .contains(UnbookableReason.SLOT_TAKEN);
        assertThat(check("11:00", service(60), defaultConfig(), busyLika, clockAt(NOW)))
                .isEmpty();
    }

    @Test
    @DisplayName("time off takes the slot")
    void taken_by_time_off() {
        EmployeeAvailabilityInput away = employee(
                LIKA, "Lika", weekdays("09:00", "17:00"), List.of(busy(MONDAY, "10:00", "11:00", TBILISI)), List.of());

        assertThat(check("10:00", service(60), defaultConfig(), away, clockAt(NOW)))
                .contains(UnbookableReason.SLOT_TAKEN);
    }

    @Test
    @DisplayName("a closure takes the slot")
    void taken_by_a_closure() {
        BusinessSchedulingConfig closed = config(
                TBILISI,
                weekdays("09:00", "17:00"),
                30,
                0,
                60,
                TimeRange.of(at(MONDAY, "00:00", TBILISI), at(MONDAY.plusDays(1), "00:00", TBILISI)));

        assertThat(check("10:00", service(60), closed, lika(), clockAt(NOW)))
                .contains(UnbookableReason.SLOT_TAKEN);
    }

    @Test
    @DisplayName("a buffer that collides with a neighbouring appointment takes the slot")
    void taken_through_a_buffer() {
        EmployeeAvailabilityInput busyLika = employee(
                LIKA, "Lika", weekdays("09:00", "17:00"), List.of(), List.of(busy(MONDAY, "11:00", "12:00", TBILISI)));

        assertThat(check("10:00", service(60, 0, 30), defaultConfig(), busyLika, clockAt(NOW)))
                .contains(UnbookableReason.SLOT_TAKEN);
        assertThat(check("10:00", service(60), defaultConfig(), busyLika, clockAt(NOW)))
                .isEmpty();
    }

    @Test
    @DisplayName("every slot findSlots offers passes isSlotBookable — the two never disagree")
    void the_two_entry_points_agree() {
        ServiceSpec spec = service(60, 15, 15);
        BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 60, 60);
        EmployeeAvailabilityInput who = employee(
                LIKA,
                "Lika",
                weekdays("09:00", "17:00"),
                List.of(busy(MONDAY, "14:00", "15:00", TBILISI)),
                List.of(busy(MONDAY, "10:00", "11:00", TBILISI)));
        Clock clock = clockAtLocal("2026-09-14T08:00:00", TBILISI);

        AvailabilityResult offered =
                engine.findSlots(new AvailabilityQuery(spec, MONDAY, MONDAY, LIKA), config, List.of(who), clock);

        assertThat(offered.days().getFirst().slots()).isNotEmpty();
        for (Slot slot : offered.days().getFirst().slots()) {
            assertThat(engine.isSlotBookable(slot.startsAt(), spec, config, who, clock))
                    .as("slot at %s", slot.startsAt().atZone(TBILISI).toLocalTime())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("and every start it does NOT offer is refused with a reason")
    void the_two_entry_points_agree_in_the_negative() {
        ServiceSpec spec = service(60, 15, 15);
        BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 60, 60);
        EmployeeAvailabilityInput who = employee(
                LIKA,
                "Lika",
                weekdays("09:00", "17:00"),
                List.of(),
                List.of(busy(MONDAY, "10:00", "11:00", TBILISI)));
        Clock clock = clockAtLocal("2026-09-14T08:00:00", TBILISI);

        List<Instant> offered = engine
                .findSlots(new AvailabilityQuery(spec, MONDAY, MONDAY, LIKA), config, List.of(who), clock)
                .days()
                .getFirst()
                .slots()
                .stream()
                .map(Slot::startsAt)
                .toList();

        for (Instant candidate : SlotGenerator.candidateStarts(MONDAY, TBILISI, 30)) {
            if (!offered.contains(candidate)) {
                assertThat(engine.isSlotBookable(candidate, spec, config, who, clock))
                        .as("start at %s", candidate.atZone(TBILISI).toLocalTime())
                        .isPresent();
            }
        }
        assertThat(Duration.between(offered.getFirst(), offered.getLast())).isPositive();
    }
}
