package dev.reception.calendar.web;

import dev.reception.appointments.AppointmentSource;
import dev.reception.appointments.AppointmentStatus;
import dev.reception.calendar.CalendarView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * The wire shape of {@code GET /calendar}.
 *
 * <p><strong>Deliberately not {@code AppointmentResponses.AppointmentDetail}.</strong> That record
 * carries the confirmation code, the customer's phone and email, the agreed price and the audit
 * timestamps — everything the detail drawer needs, and none of what a coloured block on a grid
 * needs. Reusing it would ship a week of confirmation codes and phone numbers to the browser to
 * render rectangles, and the drawer already fetches the full row when an appointment is actually
 * opened.
 *
 * <p>So a calendar appointment carries the customer's <em>name</em> and nothing else about them.
 */
public final class CalendarResponses {

    private CalendarResponses() {}

    public record Range(LocalDate from, LocalDate to, String timezone) {}

    public record NamedRef(UUID id, String name) {}

    /**
     * @param source what booked it, which is what the AI badge is drawn from
     * @param customerName the name alone — see the class comment
     */
    public record CalendarAppointment(
            UUID id,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            AppointmentStatus status,
            NamedRef service,
            NamedRef employee,
            String customerName,
            AppointmentSource source) {}

    /** A whole-business non-bookable span. {@code employee} is absent because it covers everybody. */
    public record Closure(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, String reason) {}

    /** One Employee's non-bookable span, named so a column can say whose absence it is. */
    public record TimeOff(UUID id, NamedRef employee, OffsetDateTime startsAt, OffsetDateTime endsAt, String reason) {}

    public record Calendar(
            Range range, List<CalendarAppointment> appointments, List<Closure> closures, List<TimeOff> timeOff) {

        public static Calendar of(CalendarView view) {
            ZoneId zone = view.timezone();
            return new Calendar(
                    new Range(view.from(), view.to(), zone.getId()),
                    view.appointments().stream()
                            .map(appointment -> new CalendarAppointment(
                                    appointment.appointment().getId(),
                                    at(appointment.appointment().startsAt(), zone),
                                    at(appointment.appointment().endsAt(), zone),
                                    appointment.appointment().status(),
                                    new NamedRef(appointment.appointment().serviceId(), appointment.serviceName()),
                                    new NamedRef(appointment.appointment().employeeId(), appointment.employeeName()),
                                    appointment.customer().fullName(),
                                    appointment.appointment().source()))
                            .toList(),
                    view.closures().stream()
                            .map(closure -> new Closure(
                                    closure.getId(),
                                    at(closure.startsAt(), zone),
                                    at(closure.endsAt(), zone),
                                    closure.reason()))
                            .toList(),
                    view.timeOff().stream()
                            .map(off -> new TimeOff(
                                    off.getId(),
                                    new NamedRef(off.employeeId(), view.employeeNames().get(off.employeeId())),
                                    at(off.startsAt(), zone),
                                    at(off.endsAt(), zone),
                                    off.reason()))
                            .toList());
        }
    }

    /**
     * Every time on this endpoint is rendered in the Business's zone, offset included.
     *
     * <p>Not the browser's, and not UTC. A block positioned from an instant the client converted
     * itself would sit in the wrong row for any owner travelling, or for any receptionist whose
     * laptop clock is set to somewhere else — which is the phase's "all times everywhere display in
     * the business timezone" requirement, decided on the server so the client cannot get it wrong.
     */
    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }
}
