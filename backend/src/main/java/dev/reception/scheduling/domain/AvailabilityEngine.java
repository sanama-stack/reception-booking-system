package dev.reception.scheduling.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The heart of the product: which Slots a Service can genuinely be performed at.
 *
 * <p><strong>Pure.</strong> No repositories, no Spring, no I/O, no ambient clock — every input
 * arrives as an argument and {@code now} arrives as a {@link Clock}. {@code LayeringTest} fails the
 * build if that stops being true, and {@code NoAmbientClockTest} fails it if a {@code now()} creeps
 * in. This is not tidiness: it is what lets the suite in docs/08-testing-strategy.md §4 enumerate
 * DST transitions, buffer edges and horizon boundaries without a database or a fixed calendar date.
 *
 * <p>The algorithm is docs/01-prd.md FR-5, in order: intersect Business Hours with the Working
 * Schedule for the date, subtract nothing but test against everything busy, walk the grid, and drop
 * what falls outside the Booking Horizon.
 *
 * <p><strong>Two ranges per candidate, and they are not the same range.</strong> The Appointment
 * {@code [t, t + duration)} must fit inside a single workable stretch; the occupancy
 * {@code [t − bufferBefore, t + duration + bufferAfter)} must merely not collide with anything busy.
 * Requiring the Buffers to fit inside opening hours too would delete the last Slot of every single
 * day — a 60-minute Service ending exactly at 18:00 would stop being offered because of ten minutes
 * of cleanup nobody is waiting for — and owners read that as a defect (CONTEXT.md, FR-5).
 */
public final class AvailabilityEngine {

    /**
     * Every bookable Slot in the range, one entry per requested date.
     *
     * @param employees the Employees who may perform this Service. Eligibility — active, assigned,
     *     same Business — is a database question answered before the engine is called; an empty list
     *     means nobody, and the answer is {@link EmptyReason#NO_ELIGIBLE_EMPLOYEE}
     */
    public AvailabilityResult findSlots(
            AvailabilityQuery query,
            BusinessSchedulingConfig config,
            List<EmployeeAvailabilityInput> employees,
            Clock clock) {

        ZoneId zone = config.timezone();
        Instant now = clock.instant();
        Instant earliest = now.plus(Duration.ofMinutes(config.minLeadTimeMinutes()));
        Instant latest = now.plus(Duration.ofDays(config.maxAdvanceDays()));

        List<LocalDate> dates = query.dates();
        List<EmployeeAvailabilityInput> candidates = eligible(employees, query.employeeId());
        if (candidates.isEmpty()) {
            return AvailabilityResult.of(emptyDays(dates), EmptyReason.NO_ELIGIBLE_EMPLOYEE);
        }

        // Three facts, tracked so an empty answer can say which of the three things went wrong.
        // Two of them are deliberately about what would have been possible ignoring the horizon:
        // that is what separates "you asked about a week we do not take bookings for" from "we are
        // shut", and the two send an owner to different screens.
        boolean anyCandidateInHorizon = false;
        boolean anyCandidateFits = false;
        boolean anyCandidateFitsInHorizon = false;

        List<Slot.Day> days = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            List<Instant> grid = SlotGenerator.candidateStarts(date, zone, config.slotIntervalMinutes());
            anyCandidateInHorizon |= grid.stream().anyMatch(start -> withinHorizon(start, earliest, latest));

            List<TimeRange> open = intervalsOn(config.openIntervals(), date, zone);

            // A TreeMap because a Slot's identity is its start instant: two Employees free at 10:00
            // are one Slot offered to the Customer, and the map keeps them in time order for free.
            Map<Instant, EmployeeAvailabilityInput> chosen = new TreeMap<>();
            for (EmployeeAvailabilityInput employee : byPreference(candidates, date, zone)) {
                List<TimeRange> workable = TimeRange.intersect(open, intervalsOn(employee.workingIntervals(), date, zone));
                List<TimeRange> busy = busyRanges(employee, config);
                for (Instant start : grid) {
                    TimeRange appointment = TimeRange.of(start, query.service().duration());
                    // Whether the Service could have gone here at all, before the horizon or
                    // anything busy is considered. Deliberately about the Service and not merely
                    // about the window: an hour of opening time cannot hold a two-hour Service, and
                    // reporting FULLY_BOOKED when nothing is booked sends an owner looking for a
                    // cancellation that would not help.
                    boolean fits = workable.stream().anyMatch(window -> window.contains(appointment));
                    boolean inHorizon = withinHorizon(start, earliest, latest);
                    anyCandidateFits |= fits;
                    anyCandidateFitsInHorizon |= fits && inHorizon;
                    if (fits
                            && inHorizon
                            && !TimeRange.overlapsAny(query.service().occupancyFor(appointment), busy)) {
                        // The Employees were ordered by preference above, so the first one to claim
                        // a start keeps it. That is the whole tie-break.
                        chosen.putIfAbsent(start, employee);
                    }
                }
            }

            List<Slot> slots = chosen.entrySet().stream()
                    .map(entry -> new Slot(
                            entry.getKey(),
                            entry.getKey().plus(query.service().duration()),
                            entry.getValue().employeeId(),
                            entry.getValue().fullName()))
                    .toList();
            days.add(new Slot.Day(date, slots));
        }

        return AvailabilityResult.of(days, emptyReason(anyCandidateInHorizon, anyCandidateFits, anyCandidateFitsInHorizon));
    }

    /**
     * Whether one specific start is bookable by one specific Employee, and if not, why.
     *
     * <p>Phase 06 calls this before writing, because the Slot list a client is looking at may be
     * seconds stale. It is <em>not</em> the last line of defence — the exclusion constraint is
     * (ADR-0002) — but it is what turns a lost race into a sentence a person can act on rather than
     * a constraint violation.
     *
     * <p>The checks run cheapest and most-explanatory first, and the first failure wins: someone
     * booking last Tuesday should be told the date has passed, not that the shop is shut.
     *
     * @return empty when the Slot is bookable
     */
    public Optional<UnbookableReason> isSlotBookable(
            Instant start,
            ServiceSpec service,
            BusinessSchedulingConfig config,
            EmployeeAvailabilityInput employee,
            Clock clock) {

        ZoneId zone = config.timezone();
        Instant now = clock.instant();
        if (start.isBefore(now)) {
            return Optional.of(UnbookableReason.BOOKING_IN_PAST);
        }
        if (start.isBefore(now.plus(Duration.ofMinutes(config.minLeadTimeMinutes())))) {
            return Optional.of(UnbookableReason.BELOW_MIN_LEAD_TIME);
        }
        if (start.isAfter(now.plus(Duration.ofDays(config.maxAdvanceDays())))) {
            return Optional.of(UnbookableReason.BEYOND_MAX_ADVANCE);
        }

        LocalDate date = start.atZone(zone).toLocalDate();
        if (!SlotGenerator.candidateStarts(date, zone, config.slotIntervalMinutes()).contains(start)) {
            return Optional.of(UnbookableReason.NOT_ON_SLOT_GRID);
        }

        TimeRange appointment = TimeRange.of(start, service.duration());
        // Business Hours and Working Schedule are tested separately rather than as their
        // intersection, purely so the refusal can name which one was missed. findSlots has no such
        // need and intersects them once.
        if (intervalsOn(config.openIntervals(), date, zone).stream().noneMatch(open -> open.contains(appointment))) {
            return Optional.of(UnbookableReason.OUTSIDE_BUSINESS_HOURS);
        }
        if (intervalsOn(employee.workingIntervals(), date, zone).stream()
                .noneMatch(working -> working.contains(appointment))) {
            return Optional.of(UnbookableReason.OUTSIDE_WORKING_HOURS);
        }
        if (TimeRange.overlapsAny(service.occupancyFor(appointment), busyRanges(employee, config))) {
            return Optional.of(UnbookableReason.SLOT_TAKEN);
        }
        return Optional.empty();
    }

    /**
     * Which of the three explanations to give, in the order the constants are declared.
     *
     * <p>{@code OUTSIDE_HORIZON} wins twice: when no moment in the requested dates is bookable at
     * all — someone asking about last Sunday should not be told the shop is shut on Sundays — and
     * when the Service <em>would</em> have fitted somewhere in the range and every one of those
     * places is out of bounds. Only once the horizon is exonerated is "we are closed" the truth.
     */
    private static EmptyReason emptyReason(boolean anyCandidateInHorizon, boolean anyFits, boolean anyFitsInHorizon) {
        if (!anyCandidateInHorizon || (anyFits && !anyFitsInHorizon)) {
            return EmptyReason.OUTSIDE_HORIZON;
        }
        return anyFitsInHorizon ? EmptyReason.FULLY_BOOKED : EmptyReason.CLOSED;
    }

    private static boolean withinHorizon(Instant start, Instant earliest, Instant latest) {
        return !start.isBefore(earliest) && !start.isAfter(latest);
    }

    private static List<EmployeeAvailabilityInput> eligible(
            List<EmployeeAvailabilityInput> employees, UUID requested) {
        if (requested == null) {
            return employees;
        }
        return employees.stream()
                .filter(employee -> employee.employeeId().equals(requested))
                .toList();
    }

    /**
     * Fewest Appointments that day, then lexicographic id — the deterministic tie-break FR-5
     * specifies, so two identical requests return identical answers.
     *
     * <p>Compared as strings rather than by {@link UUID#compareTo}, which orders by two signed longs
     * and is <em>not</em> the lexicographic order of the id anybody can see. Either is
     * deterministic; only one of them matches what the specification says and what a person reading
     * two ids would predict.
     */
    private static List<EmployeeAvailabilityInput> byPreference(
            List<EmployeeAvailabilityInput> employees, LocalDate date, ZoneId zone) {
        TimeRange day = dayRange(date, zone);
        Map<UUID, Long> load = new LinkedHashMap<>();
        for (EmployeeAvailabilityInput employee : employees) {
            load.put(
                    employee.employeeId(),
                    employee.appointments().stream().filter(day::overlaps).count());
        }
        return employees.stream()
                .sorted(Comparator.comparingLong((EmployeeAvailabilityInput e) -> load.get(e.employeeId()))
                        .thenComparing(e -> e.employeeId().toString()))
                .toList();
    }

    /** The whole calendar date as real time — 23, 24 or 25 hours long, depending on the date. */
    private static TimeRange dayRange(LocalDate date, ZoneId zone) {
        return new TimeRange(
                WallClock.boundary(date.atStartOfDay(), zone), WallClock.boundary(date.plusDays(1).atStartOfDay(), zone));
    }

    /**
     * The weekly intervals that apply to this date, as real time, merged.
     *
     * <p>Merged because two rows that touch describe one continuous stretch, and a Service must be
     * allowed to span the join. See {@link TimeRange#union}.
     */
    private static List<TimeRange> intervalsOn(Collection<WeeklyInterval> intervals, LocalDate date, ZoneId zone) {
        return TimeRange.union(
                intervals.stream().map(interval -> interval.on(date, zone)).flatMap(Optional::stream).toList());
    }

    /**
     * Everything that makes this Employee unavailable, in one list.
     *
     * <p>Appointments, Time Off and Business Closures are three different facts with three different
     * owners, and they are all stored as instants for exactly this reason (ADR-0003): the engine
     * subtracts them with one piece of arithmetic instead of three.
     */
    private static List<TimeRange> busyRanges(EmployeeAvailabilityInput employee, BusinessSchedulingConfig config) {
        List<TimeRange> busy = new ArrayList<>(
                employee.appointments().size() + employee.timeOff().size() + config.closures().size());
        busy.addAll(employee.appointments());
        busy.addAll(employee.timeOff());
        busy.addAll(config.closures());
        return busy;
    }

    private static List<Slot.Day> emptyDays(List<LocalDate> dates) {
        return dates.stream().map(date -> new Slot.Day(date, List.of())).toList();
    }
}
