package dev.reception.seed;

import dev.reception.appointments.AppointmentSource;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;

/**
 * What a demo tenant is, as data.
 *
 * <p>Declarative on purpose. Every number a reviewer might ask about — how many services, which
 * employee cannot do Colour, which week somebody is away — is visible in {@link DemoTenants}
 * rather than distributed through the code that writes it, and {@link TenantSeeder} is then the
 * one place that knows how to turn any of this into rows.
 *
 * <p><strong>Appointments are anchored to the week, not to a date.</strong> A seed that said "three
 * days ago" would land on a Sunday one run in seven and on a day the Employee does not work in
 * three, and the failure would look like a scheduling defect rather than a fixture that cannot
 * count. {@link Placement} names a weekday and an offset in weeks from the Monday of the week the
 * seed runs in, so the same appointment falls on the same weekday every time.
 */
final class Blueprint {

    private Blueprint() {}

    /** One demo tenant, whole. */
    record Tenant(
            Owner owner,
            Profile profile,
            List<Interval> hours,
            List<Service> services,
            List<Employee> employees,
            List<Faq> faqs,
            List<Closure> closures,
            List<Customer> customers,
            List<Appointment> appointments) {}

    /**
     * The account that signs in. Created through the same registration the public form calls, so a
     * demo login is a real one rather than a row arranged to look like one.
     */
    record Owner(String email, String password, String fullName) {}

    /** Everything {@code PATCH /business} carries, plus the booking policy the engine reads. */
    record Profile(
            String name,
            String slug,
            ZoneId timezone,
            String currency,
            String country,
            String description,
            String addressLine,
            String city,
            String phone,
            String email,
            String website,
            String cancellationPolicy,
            String aiAdditionalInfo,
            int slotIntervalMinutes,
            int minLeadTimeMinutes,
            int maxAdvanceDays,
            int cancellationWindowHours) {}

    /** A weekly interval, used for both Business Hours and a Working Schedule. */
    record Interval(DayOfWeek day, LocalTime from, LocalTime to) {}

    record Service(
            String name,
            String description,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            BigDecimal price) {}

    /**
     * @param services the Services this Employee performs, by name. An Employee left off a Service
     *     cannot be booked for it — which is the state Salon Aria's barber is in, deliberately
     */
    record Employee(
            String fullName,
            String email,
            String phone,
            String jobTitle,
            List<Interval> schedule,
            List<String> services,
            List<TimeOff> timeOff) {}

    /** Whole days, inclusive of both ends, in the Business's own timezone. */
    record TimeOff(int weekOffset, DayOfWeek from, DayOfWeek to, String reason) {}

    /**
     * A Business Closure on a fixed calendar day — a public holiday. Resolved to its next
     * occurrence on or after the day the seed runs, so the demo still has a closure ahead of it in
     * March as well as in September.
     */
    record Closure(MonthDay day, String reason) {}

    record Faq(String question, String answer) {}

    /** The phone is written as a person would give it; normalisation to E.164 is the application's. */
    record Customer(String fullName, String phone, String email) {}

    /** Monday of the current week in the Business's zone, plus {@code weekOffset} weeks, at {@code day} {@code at}. */
    record Placement(int weekOffset, DayOfWeek day, LocalTime at) {}

    record Appointment(
            Placement when,
            String employee,
            String service,
            String customer,
            AppointmentSource source,
            Outcome outcome,
            String note) {}

    /**
     * What became of an Appointment.
     *
     * <p>{@link #COMPLETED} and {@link #NO_SHOW} are applied only once the Appointment has actually
     * ended — a fixture that marked a future booking complete would be asserting something the
     * dashboard refuses to do. A {@link #BOOKED} one that happens to be in the past is left
     * {@code CONFIRMED} on purpose: it is the owner's unfinished business, and it is what the demo
     * script marks completed by hand.
     */
    enum Outcome {
        BOOKED,
        COMPLETED,
        NO_SHOW,
        CANCELLED_BY_CUSTOMER,
        CANCELLED_BY_BUSINESS
    }
}
