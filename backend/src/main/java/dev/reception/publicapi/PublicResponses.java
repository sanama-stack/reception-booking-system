package dev.reception.publicapi;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentStatus;
import dev.reception.business.Business;
import dev.reception.business.BusinessHours;
import dev.reception.catalog.Service;
import dev.reception.staff.Employee;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Everything the public surface is allowed to say.
 *
 * <p><strong>Hand-written, never mapped from an entity</strong>
 * (docs/phases/phase-08-public-booking.md). Mapping an entity by accident is the easiest way to leak
 * — one added column and a private field is public — so the rule is that a field appears in a
 * booking page's response because somebody typed it here, and {@code PublicFieldAllowListTest}
 * fails the build for any field that was not.
 *
 * <p>Absent by construction, not by filtering: an Employee's email and phone, every internal
 * setting, the AI cost cap, any other Customer, the Business's own id, and every id a booking does
 * not need. The ids that <em>are</em> here — Service, Employee, Appointment — are the ones a caller
 * must send back to complete or manage a booking, and none of them is useful without the tenant
 * they belong to, which no public caller can name.
 *
 * <p><strong>The Business's own contact details are here on purpose.</strong> A name, an address, a
 * phone number and an email are what a booking page exists to publish; withholding them would be
 * minimising the wrong thing. What is withheld is everything about the people who work there and
 * everyone else who has booked.
 */
public final class PublicResponses {

    private PublicResponses() {}

    /**
     * The booking page's header — who this business is, when they are open, and what they will do if
     * you cancel late.
     *
     * <p>No id. The slug in the URL is the only handle a public caller needs, and the Business's
     * primary key is never one of the things a stranger is given.
     */
    public record BusinessProfile(
            String name,
            String description,
            String addressLine,
            String city,
            String country,
            String phone,
            String email,
            String website,
            String timezone,
            String currency,
            int cancellationWindowHours,
            String cancellationPolicy,
            boolean aiEnabled,
            List<DayHours> hours) {

        public static BusinessProfile of(Business business, List<BusinessHours> hours) {
            return new BusinessProfile(
                    business.name(),
                    business.description(),
                    business.addressLine(),
                    business.city(),
                    business.country(),
                    business.phone(),
                    business.email(),
                    business.website(),
                    business.timezone().getId(),
                    business.currency(),
                    business.cancellationWindowHours(),
                    business.cancellationPolicy(),
                    // Phase 09 reads this to decide whether to render the chat panel at all. It is
                    // a fact about the page, not a setting a Customer could change.
                    business.aiEnabled(),
                    hours.stream().map(DayHours::of).toList());
        }
    }

    /**
     * One day's opening hours.
     *
     * <p>{@code HH:mm} rather than Jackson's ISO default, matching the dashboard's shape for the
     * same reason: opening hours never carry seconds and no client wants to strip them. No id —
     * a public caller has nothing to do with the row's identity.
     */
    public record DayHours(
            int dayOfWeek,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime opensAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm") LocalTime closesAt) {

        static DayHours of(BusinessHours hours) {
            return new DayHours(hours.dayOfWeek().getValue(), hours.opensAt(), hours.closesAt());
        }
    }

    /**
     * A bookable Service.
     *
     * <p>The Buffers are absent, and that is a decision rather than an oversight: they are how the
     * business runs its day, not part of what the Customer is buying, and publishing them would let
     * anyone reconstruct the real occupancy of every appointment on the calendar from the
     * availability grid.
     */
    public record ServiceSummary(
            UUID id, String name, String description, int durationMinutes, Money price) {

        public static ServiceSummary of(Service service) {
            return new ServiceSummary(
                    service.getId(),
                    service.name(),
                    service.description(),
                    service.durationMinutes(),
                    new Money(service.priceAmount(), service.currency()));
        }
    }

    /** Names and job titles only (docs/06-security.md §5). No email, no phone, no user link. */
    public record EmployeeSummary(UUID id, String fullName, String jobTitle) {

        public static EmployeeSummary of(Employee employee) {
            return new EmployeeSummary(employee.getId(), employee.fullName(), employee.jobTitle());
        }
    }

    /** A string, not a float. Money that has been through a double is money that is wrong. */
    public record Money(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency) {}

    /** What a Service is called, on an appointment that has already been booked. */
    public record BookedService(String name, int durationMinutes) {}

    /** Who is performing it. A name — the Customer is meeting a person, not an id. */
    public record BookedEmployee(String fullName) {}

    /**
     * The confirmation screen, and the shape of {@code POST …/appointments}
     * (docs/04-api-overview.md §6).
     *
     * <p>The Confirmation Code is here because this is the one moment it can be shown: the email is
     * on its way but has not arrived, and a Customer who closes this tab without it has to wait.
     *
     * @param confirmationSent whether a confirmation email was actually enqueued. <strong>One bit,
     *     and deliberately not the address</strong> (ADR-0007). A Customer is identified by phone
     *     alone, so a returning number keeps the email already on file and the message may go
     *     somewhere other than the address just typed — or nowhere, when the stored record has none.
     *     Without this field the screen has to guess, and it guessed wrong in both directions. The
     *     resolved recipient is <em>not</em> returned: echoing a stored address back would turn a
     *     phone number into a way to read it, which is the leak {@link ManagedAppointment} refuses
     *     for the same reason
     */
    public record BookedAppointment(
            UUID id,
            String confirmationCode,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timezone,
            BookedService service,
            BookedEmployee employee,
            Money price,
            boolean confirmationSent) {

        public static BookedAppointment of(
                Appointment appointment,
                Service service,
                Employee employee,
                ZoneId zone,
                boolean confirmationSent) {
            return new BookedAppointment(
                    appointment.getId(),
                    appointment.confirmationCode(),
                    at(appointment.startsAt(), zone),
                    at(appointment.endsAt(), zone),
                    zone.getId(),
                    new BookedService(service.name(), service.durationMinutes()),
                    new BookedEmployee(employee.fullName()),
                    new Money(appointment.priceAmount(), appointment.currency()),
                    confirmationSent);
        }
    }

    /**
     * What the Manage Link page and a successful lookup show.
     *
     * <p>One appointment and the business it is with. No Customer record — the person reading this
     * is the customer, and echoing back a stored name, phone and email would turn a Confirmation
     * Code into a way to read them.
     *
     * @param canCancel whether the Cancellation Window is still open <em>and</em> the appointment is
     *     in a state that can be cancelled. Sent so the page can show the business's policy beside a
     *     disabled button rather than letting the Customer press it and be refused; the endpoint
     *     checks it again regardless, because a field in a response is a hint and never a control
     * @param emailOnFile whether the Customer has an address on record. <strong>One bit, and
     *     deliberately not the address</strong> — ADR-0007's ruling for the booking response,
     *     extended to this surface by ADR-0008. Named for the fact rather than for an outcome
     *     because this record is returned by four endpoints and two of them send nothing: after a
     *     cancel or a reschedule the page reads it as "a confirmation is on its way", and on a
     *     {@code GET} or a lookup it is merely true. A Manage Link arrives by email, so the address
     *     was there when the token was issued — but the Business can clear it from the dashboard
     *     while the token is still valid, and both {@code NotificationEnqueuer.appointmentCancelled}
     *     and {@code appointmentRescheduled} return early without one
     */
    public record ManagedAppointment(
            UUID id,
            String confirmationCode,
            AppointmentStatus status,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timezone,
            BookedService service,
            BookedEmployee employee,
            Money price,
            String note,
            boolean canCancel,
            boolean canReschedule,
            boolean emailOnFile,
            ManagingBusiness business) {

        public static ManagedAppointment of(
                Appointment appointment,
                Service service,
                Employee employee,
                Business business,
                boolean windowOpen,
                boolean emailOnFile) {
            ZoneId zone = business.timezone();
            boolean live = appointment.status() == AppointmentStatus.CONFIRMED;
            return new ManagedAppointment(
                    appointment.getId(),
                    appointment.confirmationCode(),
                    appointment.status(),
                    at(appointment.startsAt(), zone),
                    at(appointment.endsAt(), zone),
                    zone.getId(),
                    new BookedService(service.name(), service.durationMinutes()),
                    new BookedEmployee(employee.fullName()),
                    new Money(appointment.priceAmount(), appointment.currency()),
                    appointment.customerNote(),
                    live && windowOpen,
                    live && windowOpen,
                    emailOnFile,
                    ManagingBusiness.of(business));
        }
    }

    /**
     * Who to call when the window has closed.
     *
     * <p>The policy text and a phone number are the whole point of this object: a refusal that says
     * "contact the business" without saying how is a dead end.
     */
    public record ManagingBusiness(
            String name, String phone, String email, String timezone, String cancellationPolicy) {

        static ManagingBusiness of(Business business) {
            return new ManagingBusiness(
                    business.name(),
                    business.phone(),
                    business.email(),
                    business.timezone().getId(),
                    business.cancellationPolicy());
        }
    }

    /** The Business's offset, carried on every instant, so no client ever consults the browser's. */
    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toOffsetDateTime();
    }
}
