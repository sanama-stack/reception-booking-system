package dev.reception.staff.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.reception.staff.Employee;
import dev.reception.staff.EmployeeSchedule;
import dev.reception.staff.EmployeeService;
import dev.reception.staff.EmployeeTimeOff;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response bodies for {@code /employees/*}, written by hand rather than mapped from entities. */
public final class EmployeeResponses {

    private EmployeeResponses() {}

    /**
     * @param serviceIds what this Employee may perform. On the list as well as the detail, so a
     *     management screen can show "assigned to nothing" without a request per row.
     */
    public record EmployeeDetail(
            UUID id,
            String fullName,
            String email,
            String phone,
            String jobTitle,
            boolean active,
            List<UUID> serviceIds,
            Instant updatedAt) {

        public static EmployeeDetail of(Employee employee, List<UUID> serviceIds) {
            return new EmployeeDetail(
                    employee.getId(),
                    employee.fullName(),
                    employee.email(),
                    employee.phone(),
                    employee.jobTitle(),
                    employee.active(),
                    serviceIds,
                    employee.updatedAt());
        }
    }

    public record EmployeeList(List<EmployeeDetail> employees) {

        public static EmployeeList of(List<Employee> employees, Map<UUID, List<UUID>> servicesByEmployee) {
            return new EmployeeList(employees.stream()
                    .map(employee ->
                            EmployeeDetail.of(employee, servicesByEmployee.getOrDefault(employee.getId(), List.of())))
                    .toList());
        }
    }

    /** Reported so a deactivation can be taken deliberately. Nothing is auto-cancelled. */
    public record BookabilityChange(EmployeeDetail employee, long affectedFutureAppointments) {

        public static BookabilityChange of(EmployeeService.BookabilityChange change, List<UUID> serviceIds) {
            return new BookabilityChange(
                    EmployeeDetail.of(change.employee(), serviceIds), change.affectedFutureAppointments());
        }
    }

    /** The set as it now stands, so a client never has to assume its submission was taken whole. */
    public record AssignedServices(List<UUID> serviceIds) {}

    /**
     * The week, plus the timezone the times are to be read in.
     *
     * <p>Same shape as {@code /business/hours}, deliberately: the two editors are the same control,
     * and phase 05 intersects the two lists.
     */
    public record WeekSchedule(String timezone, List<ScheduleInterval> schedule) {

        public static WeekSchedule of(String timezone, List<EmployeeSchedule> week) {
            return new WeekSchedule(
                    timezone, week.stream().map(ScheduleInterval::of).toList());
        }
    }

    /**
     * {@code HH:mm} — what {@code <input type="time">} both produces and expects, and what
     * docs/04-api-overview.md §5 publishes for opening hours. Jackson's ISO default would add a
     * seconds field a schedule never carries and no client wants to strip.
     */
    public record ScheduleInterval(
            UUID id,
            int dayOfWeek,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime startsAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime endsAt) {

        public static ScheduleInterval of(EmployeeSchedule schedule) {
            return new ScheduleInterval(
                    schedule.getId(), schedule.dayOfWeek().getValue(), schedule.startsAt(), schedule.endsAt());
        }
    }

    /**
     * An absence, given back as both the instants that were stored and the local dates the owner
     * entered.
     *
     * <p>Instants alone would render as the day before for a business west of UTC unless every
     * client repeated the conversion; dates alone would lose the representation the engine uses.
     * Both, plus the zone, leaves nothing to infer.
     *
     * <p>{@code endDate} is the owner's last day off — one day earlier than the stored, half-open
     * {@code endsAt}. Formatting {@code endsAt} would tell them they are away a day longer than they
     * said.
     */
    public record TimeOff(
            UUID id, Instant startsAt, Instant endsAt, LocalDate startDate, LocalDate endDate, String reason) {

        public static TimeOff of(EmployeeTimeOff timeOff, ZoneId zone) {
            return new TimeOff(
                    timeOff.getId(),
                    timeOff.startsAt(),
                    timeOff.endsAt(),
                    LocalDate.ofInstant(timeOff.startsAt(), zone),
                    LocalDate.ofInstant(timeOff.endsAt(), zone).minusDays(1),
                    timeOff.reason());
        }
    }

    public record TimeOffList(String timezone, List<TimeOff> timeOff) {

        public static TimeOffList of(ZoneId zone, List<EmployeeTimeOff> entries) {
            return new TimeOffList(
                    zone.getId(), entries.stream().map(entry -> TimeOff.of(entry, zone)).toList());
        }
    }
}
