package dev.reception.scheduling.domain;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A configured business that every engine case can vary one thing about.
 *
 * <p>Fixed clock, fixed zone, fixed dates: no case here may depend on the day it runs
 * (docs/08-testing-strategy.md §4). A suite that reads {@code LocalDate.now()} would pass every day
 * except the two in the year that matter.
 */
final class AvailabilityFixture {

    /** UTC+4 all year, so the base cases carry no daylight-saving noise. */
    static final ZoneId TBILISI = ZoneId.of("Asia/Tbilisi");

    /** EU rules, for the two cases that are about the transitions themselves. */
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** A Monday, comfortably inside the default Booking Horizon from {@link #NOW}. */
    static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

    static final LocalDate SUNDAY = LocalDate.of(2026, 9, 13);

    static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    static final UUID LIKA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID NINO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AvailabilityFixture() {}

    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    static Clock clockAtLocal(String isoLocalDateTime, ZoneId zone) {
        return clockAt(LocalDateTime.parse(isoLocalDateTime).atZone(zone).toInstant());
    }

    static Instant at(LocalDate date, String localTime, ZoneId zone) {
        return date.atTime(LocalTime.parse(localTime)).atZone(zone).toInstant();
    }

    static TimeRange busy(LocalDate date, String from, String to, ZoneId zone) {
        return TimeRange.of(at(date, from, zone), at(date, to, zone));
    }

    static WeeklyInterval on(DayOfWeek day, String from, String to) {
        return new WeeklyInterval(day, LocalTime.parse(from), LocalTime.parse(to));
    }

    /** The same interval on every weekday, which is what a default week looks like. */
    static List<WeeklyInterval> weekdays(String from, String to) {
        List<WeeklyInterval> week = new ArrayList<>();
        for (DayOfWeek day : List.of(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            week.add(on(day, from, to));
        }
        return List.copyOf(week);
    }

    static BusinessSchedulingConfig config(
            ZoneId zone, List<WeeklyInterval> open, int slotInterval, int minLead, int maxAdvance, TimeRange... closed) {
        return new BusinessSchedulingConfig(zone, open, slotInterval, minLead, maxAdvance, Arrays.asList(closed));
    }

    /** Open weekdays 09:00–17:00 in Tbilisi, on a 30-minute grid, with a wide-open horizon. */
    static BusinessSchedulingConfig defaultConfig() {
        return config(TBILISI, weekdays("09:00", "17:00"), 30, 0, 60);
    }

    static EmployeeAvailabilityInput employee(UUID id, String name, List<WeeklyInterval> working) {
        return new EmployeeAvailabilityInput(id, name, working, List.of(), List.of());
    }

    static EmployeeAvailabilityInput employee(
            UUID id, String name, List<WeeklyInterval> working, List<TimeRange> timeOff, List<TimeRange> appointments) {
        return new EmployeeAvailabilityInput(id, name, working, timeOff, appointments);
    }

    /** Lika, willing to work exactly the hours the business is open. */
    static EmployeeAvailabilityInput lika() {
        return employee(LIKA, "Lika", weekdays("09:00", "17:00"));
    }

    static ServiceSpec service(int durationMinutes) {
        return new ServiceSpec(UUID.randomUUID(), durationMinutes, 0, 0);
    }

    static ServiceSpec service(int durationMinutes, int bufferBefore, int bufferAfter) {
        return new ServiceSpec(UUID.randomUUID(), durationMinutes, bufferBefore, bufferAfter);
    }

    static AvailabilityQuery on(ServiceSpec spec, LocalDate date) {
        return new AvailabilityQuery(spec, date, date, null);
    }

    /** The local start times of every Slot in the result, as {@code HH:mm}, in order. */
    static List<String> startTimes(AvailabilityResult result, ZoneId zone) {
        return result.days().stream()
                .flatMap(day -> day.slots().stream())
                .map(slot -> slot.startsAt().atZone(zone).toLocalTime().toString())
                .toList();
    }

    static List<String> employeeNames(AvailabilityResult result) {
        return result.days().stream()
                .flatMap(day -> day.slots().stream())
                .map(Slot::employeeName)
                .toList();
    }
}
