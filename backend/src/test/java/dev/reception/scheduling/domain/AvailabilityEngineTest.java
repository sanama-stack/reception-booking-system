package dev.reception.scheduling.domain;

import static dev.reception.scheduling.domain.AvailabilityFixture.BERLIN;
import static dev.reception.scheduling.domain.AvailabilityFixture.LIKA;
import static dev.reception.scheduling.domain.AvailabilityFixture.MONDAY;
import static dev.reception.scheduling.domain.AvailabilityFixture.NINO;
import static dev.reception.scheduling.domain.AvailabilityFixture.NOW;
import static dev.reception.scheduling.domain.AvailabilityFixture.SUNDAY;
import static dev.reception.scheduling.domain.AvailabilityFixture.TBILISI;
import static dev.reception.scheduling.domain.AvailabilityFixture.at;
import static dev.reception.scheduling.domain.AvailabilityFixture.busy;
import static dev.reception.scheduling.domain.AvailabilityFixture.clockAt;
import static dev.reception.scheduling.domain.AvailabilityFixture.clockAtLocal;
import static dev.reception.scheduling.domain.AvailabilityFixture.config;
import static dev.reception.scheduling.domain.AvailabilityFixture.defaultConfig;
import static dev.reception.scheduling.domain.AvailabilityFixture.employee;
import static dev.reception.scheduling.domain.AvailabilityFixture.employeeNames;
import static dev.reception.scheduling.domain.AvailabilityFixture.lika;
import static dev.reception.scheduling.domain.AvailabilityFixture.on;
import static dev.reception.scheduling.domain.AvailabilityFixture.service;
import static dev.reception.scheduling.domain.AvailabilityFixture.startTimes;
import static dev.reception.scheduling.domain.AvailabilityFixture.weekdays;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The engine, exhaustively (docs/08-testing-strategy.md §4).
 *
 * <p>Every case here is a pure function call: no database, no Spring, no HTTP, no ambient clock.
 * That is the whole reason phase 05 exists as its own phase — when phase 06 later produces a wrong
 * Slot, the cause is either here or in the write path, and never ambiguous.
 */
class AvailabilityEngineTest {

    private final AvailabilityEngine engine = new AvailabilityEngine();

    private AvailabilityResult find(
            ServiceSpec spec, BusinessSchedulingConfig config, List<EmployeeAvailabilityInput> employees, Clock clock) {
        return engine.findSlots(on(spec, MONDAY), config, employees, clock);
    }

    private AvailabilityResult find(ServiceSpec spec, BusinessSchedulingConfig config) {
        return find(spec, config, List.of(lika()), clockAt(NOW));
    }

    @Test
    @DisplayName("the base case: an hour-long service on a 30-minute grid, 09:00 to 17:00")
    void the_base_case() {
        AvailabilityResult result = find(service(60), defaultConfig());

        assertThat(startTimes(result, TBILISI))
                .hasSize(15)
                .startsWith("09:00", "09:30")
                .endsWith("15:30", "16:00");
        assertThat(result.emptyReason()).isNull();
        assertThat(result.days()).hasSize(1);
        assertThat(result.days().getFirst().date()).isEqualTo(MONDAY);
    }

    @Test
    @DisplayName("every requested date comes back, including the ones with nothing on them")
    void every_date_is_reported() {
        AvailabilityResult result = engine.findSlots(
                new AvailabilityQuery(service(60), SUNDAY, MONDAY, null),
                defaultConfig(),
                List.of(lika()),
                clockAt(NOW));

        assertThat(result.days()).extracting(Slot.Day::date).containsExactly(SUNDAY, MONDAY);
        assertThat(result.days().getFirst().slots()).isEmpty();
        assertThat(result.days().getLast().slots()).isNotEmpty();
    }

    @Nested
    @DisplayName("basic fit")
    class BasicFit {

        @Test
        @DisplayName("a service that fits the open hour exactly is offered once")
        void fits_exactly() {
            BusinessSchedulingConfig config = config(TBILISI, List.of(on(DayOfWeek.MONDAY, "09:00", "10:00")), 30, 0, 60);

            assertThat(startTimes(find(service(60), config, List.of(lika()), clockAt(NOW)), TBILISI))
                    .containsExactly("09:00");
        }

        @Test
        @DisplayName("a service one minute too long is not offered at all")
        void one_minute_too_long() {
            BusinessSchedulingConfig config = config(TBILISI, List.of(on(DayOfWeek.MONDAY, "09:00", "10:00")), 30, 0, 60);
            AvailabilityResult result = find(service(61), config, List.of(lika()), clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            // Not FULLY_BOOKED: nothing is booked. The service simply never fits.
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.CLOSED);
        }

        @Test
        @DisplayName("the last possible start is offered — the service ends exactly at closing")
        void the_last_possible_start() {
            assertThat(startTimes(find(service(60), defaultConfig()), TBILISI)).endsWith("16:00");
        }

        @Test
        @DisplayName("a service cannot span a lunch break, even though the day is long enough")
        void cannot_span_a_gap() {
            BusinessSchedulingConfig config = config(
                    TBILISI,
                    List.of(on(DayOfWeek.MONDAY, "09:00", "12:00"), on(DayOfWeek.MONDAY, "13:00", "17:00")),
                    30,
                    0,
                    60);

            assertThat(startTimes(find(service(60), config, List.of(lika()), clockAt(NOW)), TBILISI))
                    .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "13:00", "13:30", "14:00", "14:30",
                            "15:00", "15:30", "16:00");
        }

        @Test
        @DisplayName("two touching rows are one continuous stretch a service may span")
        void touching_rows_are_one_stretch() {
            BusinessSchedulingConfig config = config(
                    TBILISI,
                    List.of(on(DayOfWeek.MONDAY, "09:00", "12:00"), on(DayOfWeek.MONDAY, "12:00", "17:00")),
                    30,
                    0,
                    60);

            assertThat(startTimes(find(service(60), config, List.of(lika()), clockAt(NOW)), TBILISI))
                    .contains("11:30")
                    .hasSize(15);
        }
    }

    @Nested
    @DisplayName("business hours intersected with the working schedule")
    class Intersection {

        @Test
        @DisplayName("a schedule wider than the hours is cut down to them")
        void wider_schedule() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(LIKA, "Lika", weekdays("07:00", "21:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).startsWith("09:00").endsWith("16:00").hasSize(15);
        }

        @Test
        @DisplayName("a schedule narrower than the hours is what limits availability")
        void narrower_schedule() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(LIKA, "Lika", weekdays("13:00", "16:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).containsExactly("13:00", "13:30", "14:00", "14:30", "15:00");
        }

        @Test
        @DisplayName("a schedule that never meets the hours produces nothing")
        void disjoint_schedule() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(LIKA, "Lika", weekdays("18:00", "22:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.CLOSED);
        }

        @Test
        @DisplayName("a partly overlapping schedule leaves only the overlap")
        void partial_overlap() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(LIKA, "Lika", weekdays("15:00", "20:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).containsExactly("15:00", "15:30", "16:00");
        }

        @Test
        @DisplayName("an employee scheduled on a day the business is shut produces nothing")
        void scheduled_on_a_closed_day() {
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), SUNDAY, SUNDAY, null),
                    defaultConfig(),
                    List.of(employee(LIKA, "Lika", List.of(on(DayOfWeek.SUNDAY, "09:00", "17:00")))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.CLOSED);
        }
    }

    @Nested
    @DisplayName("existing appointments")
    class ExistingAppointments {

        private AvailabilityResult withAppointmentAt(String from, String to) {
            return find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(
                            LIKA,
                            "Lika",
                            weekdays("09:00", "17:00"),
                            List.of(),
                            List.of(busy(MONDAY, from, to, TBILISI)))),
                    clockAt(NOW));
        }

        @Test
        @DisplayName("back-to-back is offered on both sides — touching is not overlapping")
        void abutting_slots_are_both_offered() {
            List<String> starts = startTimes(withAppointmentAt("10:00", "11:00"), TBILISI);

            assertThat(starts).contains("09:00", "11:00");
            assertThat(starts).doesNotContain("09:30", "10:00", "10:30");
        }

        @Test
        @DisplayName("a slot ending exactly when the appointment starts is offered")
        void a_slot_before_is_offered() {
            assertThat(startTimes(withAppointmentAt("12:00", "13:00"), TBILISI)).contains("11:00");
        }

        @Test
        @DisplayName("an appointment containing a candidate removes it")
        void a_containing_appointment_removes_the_slot() {
            assertThat(startTimes(withAppointmentAt("09:00", "17:00"), TBILISI)).isEmpty();
        }

        @Test
        @DisplayName("a partially overlapping appointment removes only what it touches")
        void partial_overlap_removes_only_what_it_touches() {
            List<String> starts = startTimes(withAppointmentAt("12:15", "12:45"), TBILISI);

            assertThat(starts).doesNotContain("11:30", "12:00", "12:30");
            assertThat(starts).contains("11:00", "13:00");
        }

        @Test
        @DisplayName("an appointment outside the day changes nothing")
        void an_appointment_elsewhere_changes_nothing() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(
                            LIKA,
                            "Lika",
                            weekdays("09:00", "17:00"),
                            List.of(),
                            List.of(busy(MONDAY.plusDays(1), "09:00", "17:00", TBILISI)))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).hasSize(15);
        }

        @Test
        @DisplayName("when everything that fits is taken, the reason is FULLY_BOOKED")
        void fully_booked() {
            AvailabilityResult result = withAppointmentAt("09:00", "17:00");

            assertThat(result.emptyReason()).isEqualTo(EmptyReason.FULLY_BOOKED);
        }
    }

    @Nested
    @DisplayName("buffers")
    class Buffers {

        private AvailabilityResult withBuffers(int before, int after, String busyFrom, String busyTo) {
            return find(
                    service(60, before, after),
                    defaultConfig(),
                    List.of(employee(
                            LIKA,
                            "Lika",
                            weekdays("09:00", "17:00"),
                            List.of(),
                            List.of(busy(MONDAY, busyFrom, busyTo, TBILISI)))),
                    clockAt(NOW));
        }

        @Test
        @DisplayName("a trailing buffer blocks the slot that would start on top of it")
        void a_trailing_buffer_blocks_the_next_slot() {
            List<String> starts = startTimes(withBuffers(0, 30, "12:00", "13:00"), TBILISI);

            // 11:00 would end at 12:00 and then need thirty minutes of cleanup, which the 12:00
            // appointment is already occupying.
            assertThat(starts).doesNotContain("11:00");
            assertThat(starts).contains("10:30");
        }

        @Test
        @DisplayName("a leading buffer blocks the slot that would start too soon after")
        void a_leading_buffer_blocks_the_previous_slot() {
            List<String> starts = startTimes(withBuffers(30, 0, "12:00", "13:00"), TBILISI);

            assertThat(starts).doesNotContain("13:00");
            assertThat(starts).contains("13:30");
        }

        @Test
        @DisplayName("buffers on both sides block on both sides")
        void buffers_on_both_sides() {
            List<String> starts = startTimes(withBuffers(15, 15, "12:00", "13:00"), TBILISI);

            assertThat(starts).doesNotContain("11:00", "11:30", "12:00", "12:30", "13:00");
            assertThat(starts).contains("10:30", "13:30");
        }

        @Test
        @DisplayName("a trailing buffer past closing does NOT remove the final slot of the day")
        void a_trailing_buffer_past_closing_keeps_the_last_slot() {
            // The one buffer rule an implementation is most likely to get wrong, and the symptom is
            // an owner reporting that "the last appointment of the day disappeared" (FR-5).
            AvailabilityResult result = find(service(60, 0, 30), defaultConfig());

            assertThat(startTimes(result, TBILISI)).endsWith("16:00").hasSize(15);
        }

        @Test
        @DisplayName("a leading buffer before opening does not remove the first slot either")
        void a_leading_buffer_before_opening_keeps_the_first_slot() {
            assertThat(startTimes(find(service(60, 30, 0), defaultConfig()), TBILISI)).startsWith("09:00");
        }

        @Test
        @DisplayName("a buffer bigger than the whole day still only blocks against what is busy")
        void an_enormous_buffer_blocks_nothing_by_itself() {
            assertThat(startTimes(find(service(60, 600, 600), defaultConfig()), TBILISI)).hasSize(15);
        }
    }

    @Nested
    @DisplayName("the slot grid")
    class Grid {

        @Test
        @DisplayName("15, 30 and 60 minute grids all start at 09:00 and end at 16:00")
        void three_grids() {
            assertThat(startTimes(find(service(60), config(TBILISI, weekdays("09:00", "17:00"), 15, 0, 60)), TBILISI))
                    .hasSize(29)
                    .startsWith("09:00", "09:15")
                    .endsWith("16:00");
            assertThat(startTimes(find(service(60), config(TBILISI, weekdays("09:00", "17:00"), 30, 0, 60)), TBILISI))
                    .hasSize(15);
            assertThat(startTimes(find(service(60), config(TBILISI, weekdays("09:00", "17:00"), 60, 0, 60)), TBILISI))
                    .hasSize(8)
                    .containsExactly("09:00", "10:00", "11:00", "12:00", "13:00", "14:00", "15:00", "16:00");
        }

        @Test
        @DisplayName("a duration that is not a multiple of the grid still lands on the grid")
        void duration_off_the_grid() {
            // 45 minutes on a 30-minute grid: starts stay on the half hour, and 16:30 is dropped
            // because it would run to 17:15.
            assertThat(startTimes(find(service(45), defaultConfig()), TBILISI))
                    .hasSize(15)
                    .startsWith("09:00", "09:30")
                    .endsWith("16:00");
        }

        @Test
        @DisplayName("opening on a time the grid does not touch loses the part before the first candidate")
        void hours_off_the_grid() {
            BusinessSchedulingConfig config = config(TBILISI, List.of(on(DayOfWeek.MONDAY, "09:10", "12:00")), 30, 0, 60);

            assertThat(startTimes(find(service(60), config, List.of(lika()), clockAt(NOW)), TBILISI))
                    .containsExactly("09:30", "10:00", "10:30", "11:00");
        }
    }

    @Nested
    @DisplayName("the booking horizon")
    class Horizon {

        @Test
        @DisplayName("slots already past are dropped, the rest of the day stays")
        void past_slots_are_dropped() {
            AvailabilityResult result =
                    find(service(60), defaultConfig(), List.of(lika()), clockAtLocal("2026-09-14T12:00:00", TBILISI));

            assertThat(startTimes(result, TBILISI)).containsExactly("12:00", "12:30", "13:00", "13:30", "14:00",
                    "14:30", "15:00", "15:30", "16:00");
        }

        @Test
        @DisplayName("a slot exactly at now plus the lead time is allowed")
        void exactly_at_the_lead_time() {
            BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 60, 60);
            AvailabilityResult result =
                    find(service(60), config, List.of(lika()), clockAtLocal("2026-09-14T09:00:00", TBILISI));

            assertThat(startTimes(result, TBILISI)).startsWith("10:00");
        }

        @Test
        @DisplayName("one minute inside the lead time is rejected")
        void one_minute_inside_the_lead_time() {
            BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 60, 60);
            AvailabilityResult result =
                    find(service(60), config, List.of(lika()), clockAtLocal("2026-09-14T09:01:00", TBILISI));

            assertThat(startTimes(result, TBILISI)).startsWith("10:30").doesNotContain("10:00");
        }

        @Test
        @DisplayName("a slot exactly at the maximum advance is allowed")
        void exactly_at_the_maximum_advance() {
            BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 0, 7);
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), MONDAY.plusDays(7), MONDAY.plusDays(7), null),
                    config,
                    List.of(lika()),
                    clockAtLocal("2026-09-14T09:00:00", TBILISI));

            assertThat(startTimes(result, TBILISI)).containsExactly("09:00");
        }

        @Test
        @DisplayName("one minute beyond the maximum advance is rejected")
        void one_minute_beyond_the_maximum_advance() {
            BusinessSchedulingConfig config = config(TBILISI, weekdays("09:00", "17:00"), 30, 0, 7);
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), MONDAY.plusDays(7), MONDAY.plusDays(7), null),
                    config,
                    List.of(lika()),
                    clockAtLocal("2026-09-14T08:59:00", TBILISI));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.OUTSIDE_HORIZON);
        }

        @Test
        @DisplayName("a range entirely in the past is empty and not an error")
        void a_range_in_the_past() {
            AvailabilityResult result =
                    find(service(60), defaultConfig(), List.of(lika()), clockAt(Instant.parse("2027-01-01T00:00:00Z")));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.OUTSIDE_HORIZON);
        }
    }

    @Nested
    @DisplayName("time off and closures")
    class AwayFromWork {

        private AvailabilityResult withTimeOff(TimeRange... off) {
            return find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(LIKA, "Lika", weekdays("09:00", "17:00"), List.of(off), List.of())),
                    clockAt(NOW));
        }

        @Test
        @DisplayName("a full day off removes the whole day")
        void a_full_day_off() {
            AvailabilityResult result =
                    withTimeOff(TimeRange.of(at(MONDAY, "00:00", TBILISI), at(MONDAY.plusDays(1), "00:00", TBILISI)));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.FULLY_BOOKED);
        }

        @Test
        @DisplayName("an afternoon off leaves the morning")
        void a_partial_day_off() {
            assertThat(startTimes(withTimeOff(busy(MONDAY, "13:00", "17:00", TBILISI)), TBILISI))
                    .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00");
        }

        @Test
        @DisplayName("a multi-day absence removes every day it covers and no more")
        void a_multi_day_absence() {
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), MONDAY, MONDAY.plusDays(2), null),
                    defaultConfig(),
                    List.of(employee(
                            LIKA,
                            "Lika",
                            weekdays("09:00", "17:00"),
                            List.of(TimeRange.of(
                                    at(MONDAY, "00:00", TBILISI), at(MONDAY.plusDays(2), "00:00", TBILISI))),
                            List.of())),
                    clockAt(NOW));

            assertThat(result.days()).extracting(day -> day.slots().size()).containsExactly(0, 0, 15);
        }

        @Test
        @DisplayName("a closure removes availability for every employee, not just one")
        void a_closure_covers_everybody() {
            AvailabilityResult result = find(
                    service(60),
                    config(
                            TBILISI,
                            weekdays("09:00", "17:00"),
                            30,
                            0,
                            60,
                            TimeRange.of(at(MONDAY, "00:00", TBILISI), at(MONDAY.plusDays(1), "00:00", TBILISI))),
                    List.of(employee(LIKA, "Lika", weekdays("09:00", "17:00")), employee(NINO, "Nino", weekdays("09:00", "17:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).isEmpty();
            assertThat(result.emptyReason()).isEqualTo(EmptyReason.FULLY_BOOKED);
        }
    }

    @Nested
    @DisplayName("more than one employee")
    class MultipleEmployees {

        @Test
        @DisplayName("two different schedules produce one merged list, each slot named correctly")
        void two_schedules_merge() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(
                            employee(LIKA, "Lika", weekdays("09:00", "13:00")),
                            employee(NINO, "Nino", weekdays("13:00", "17:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI))
                    .containsExactly("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "13:00", "13:30",
                            "14:00", "14:30", "15:00", "15:30", "16:00");
            assertThat(employeeNames(result))
                    .startsWith("Lika", "Lika")
                    .endsWith("Nino", "Nino");
        }

        @Test
        @DisplayName("an employee on time off drops out and the other still answers")
        void one_employee_away() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(
                            employee(
                                    LIKA,
                                    "Lika",
                                    weekdays("09:00", "17:00"),
                                    List.of(TimeRange.of(
                                            at(MONDAY, "00:00", TBILISI), at(MONDAY.plusDays(1), "00:00", TBILISI))),
                                    List.of()),
                            employee(NINO, "Nino", weekdays("09:00", "17:00"))),
                    clockAt(NOW));

            assertThat(startTimes(result, TBILISI)).hasSize(15);
            assertThat(employeeNames(result)).containsOnly("Nino");
        }

        @Test
        @DisplayName("the tie-break prefers the employee with fewer appointments that day")
        void tie_break_prefers_the_quieter_employee() {
            // LIKA sorts first lexicographically, and still loses: she already has an appointment.
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(
                            employee(
                                    LIKA,
                                    "Lika",
                                    weekdays("09:00", "17:00"),
                                    List.of(),
                                    List.of(busy(MONDAY, "09:00", "10:00", TBILISI))),
                            employee(NINO, "Nino", weekdays("09:00", "17:00"))),
                    clockAt(NOW));

            assertThat(employeeNames(result)).containsOnly("Nino");
        }

        @Test
        @DisplayName("with equal load the tie-break is the lexicographically smaller id")
        void tie_break_falls_through_to_the_id() {
            AvailabilityResult result = find(
                    service(60),
                    defaultConfig(),
                    List.of(employee(NINO, "Nino", weekdays("09:00", "17:00")), employee(LIKA, "Lika", weekdays("09:00", "17:00"))),
                    clockAt(NOW));

            assertThat(employeeNames(result)).containsOnly("Lika");
        }

        @Test
        @DisplayName("the tie-break is stable across repeated runs and input orderings")
        void tie_break_is_deterministic() {
            List<EmployeeAvailabilityInput> one =
                    List.of(employee(LIKA, "Lika", weekdays("09:00", "17:00")), employee(NINO, "Nino", weekdays("09:00", "17:00")));
            List<EmployeeAvailabilityInput> reversed = one.reversed();

            for (int run = 0; run < 5; run++) {
                assertThat(employeeNames(find(service(60), defaultConfig(), one, clockAt(NOW))))
                        .isEqualTo(employeeNames(find(service(60), defaultConfig(), reversed, clockAt(NOW))));
            }
        }

        @Test
        @DisplayName("asking for one employee answers only about them")
        void asking_for_one_employee() {
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), MONDAY, MONDAY, NINO),
                    defaultConfig(),
                    List.of(
                            employee(LIKA, "Lika", weekdays("09:00", "17:00")),
                            employee(NINO, "Nino", weekdays("13:00", "17:00"))),
                    clockAt(NOW));

            assertThat(employeeNames(result)).containsOnly("Nino");
            assertThat(startTimes(result, TBILISI))
                    .containsExactly("13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00");
        }
    }

    @Nested
    @DisplayName("empty reasons")
    class EmptyReasons {

        @Test
        @DisplayName("nobody can perform the service")
        void no_eligible_employee() {
            assertThat(find(service(60), defaultConfig(), List.of(), clockAt(NOW)).emptyReason())
                    .isEqualTo(EmptyReason.NO_ELIGIBLE_EMPLOYEE);
        }

        @Test
        @DisplayName("the employee asked for is not among the eligible ones")
        void an_unknown_employee_is_nobody() {
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), MONDAY, MONDAY, NINO),
                    defaultConfig(),
                    List.of(lika()),
                    clockAt(NOW));

            assertThat(result.emptyReason()).isEqualTo(EmptyReason.NO_ELIGIBLE_EMPLOYEE);
        }

        @Test
        @DisplayName("the business is shut that day")
        void closed() {
            AvailabilityResult result = engine.findSlots(
                    new AvailabilityQuery(service(60), SUNDAY, SUNDAY, null),
                    defaultConfig(),
                    List.of(lika()),
                    clockAt(NOW));

            assertThat(result.emptyReason()).isEqualTo(EmptyReason.CLOSED);
        }

        @Test
        @DisplayName("no business hours at all")
        void no_hours_configured() {
            AvailabilityResult result =
                    find(service(60), config(TBILISI, List.of(), 30, 0, 60), List.of(lika()), clockAt(NOW));

            assertThat(result.emptyReason()).isEqualTo(EmptyReason.CLOSED);
        }

        @Test
        @DisplayName("an employee with no working schedule")
        void no_schedule_configured() {
            AvailabilityResult result =
                    find(service(60), defaultConfig(), List.of(employee(LIKA, "Lika", List.of())), clockAt(NOW));

            assertThat(result.emptyReason()).isEqualTo(EmptyReason.CLOSED);
        }

        @Test
        @DisplayName("a reason is never set when a slot was found")
        void no_reason_when_slots_exist() {
            assertThat(find(service(60), defaultConfig()).emptyReason()).isNull();
        }
    }

    @Nested
    @DisplayName("daylight saving")
    class DaylightSaving {

        /** Open 00:00–06:00 on the transition day itself, which is a Sunday in both cases. */
        private BusinessSchedulingConfig overnight(ZoneId zone) {
            return config(zone, List.of(on(DayOfWeek.SUNDAY, "00:00", "06:00")), 60, 0, 365);
        }

        private EmployeeAvailabilityInput allNight() {
            return employee(LIKA, "Lika", List.of(on(DayOfWeek.SUNDAY, "00:00", "06:00")));
        }

        private AvailabilityResult onDate(LocalDate date, ZoneId zone, int durationMinutes) {
            return engine.findSlots(
                    new AvailabilityQuery(service(durationMinutes), date, date, null),
                    overnight(zone),
                    List.of(allNight()),
                    clockAt(Instant.parse("2026-01-01T00:00:00Z")));
        }

        @Test
        @DisplayName("spring forward: the local times that do not exist are skipped")
        void spring_forward() {
            AvailabilityResult result = onDate(LocalDate.of(2026, 3, 29), BERLIN, 60);

            assertThat(startTimes(result, BERLIN)).containsExactly("00:00", "01:00", "03:00", "04:00", "05:00");
        }

        @Test
        @DisplayName("spring forward: a booking spanning the gap is two real hours, not three")
        void a_booking_spanning_the_gap() {
            AvailabilityResult result = onDate(LocalDate.of(2026, 3, 29), BERLIN, 120);
            Slot spanning = result.days().getFirst().slots().stream()
                    .filter(slot -> slot.startsAt().atZone(BERLIN).getHour() == 1)
                    .findFirst()
                    .orElseThrow();

            assertThat(Duration.between(spanning.startsAt(), spanning.endsAt())).isEqualTo(Duration.ofHours(2));
            // Two real hours, and the wall clock says three: 01:00 to 04:00.
            assertThat(spanning.endsAt().atZone(BERLIN).getHour()).isEqualTo(4);
        }

        @Test
        @DisplayName("fall back: the repeated hour is offered once, at its first occurrence")
        void fall_back() {
            AvailabilityResult result = onDate(LocalDate.of(2026, 10, 25), BERLIN, 60);

            assertThat(startTimes(result, BERLIN))
                    .containsExactly("00:00", "01:00", "02:00", "03:00", "04:00", "05:00");
            assertThat(result.days().getFirst().slots()).extracting(Slot::startsAt)
                    .contains(Instant.parse("2026-10-25T00:00:00Z"))
                    .doesNotContain(Instant.parse("2026-10-25T01:00:00Z"));
        }

        @Test
        @DisplayName("a zone with no daylight saving answers identically on both transition dates")
        void a_zone_without_dst() {
            for (LocalDate date : List.of(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
                assertThat(startTimes(onDate(date, TBILISI, 60), TBILISI))
                        .as("%s in Tbilisi", date)
                        .containsExactly("00:00", "01:00", "02:00", "03:00", "04:00", "05:00");
            }
        }
    }
}
