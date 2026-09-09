package dev.reception.appointments.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import dev.reception.appointments.ActorType;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentEvent;
import dev.reception.appointments.AppointmentEventType;
import dev.reception.appointments.AppointmentQueryService;
import dev.reception.appointments.AppointmentSource;
import dev.reception.appointments.AppointmentStatus;
import dev.reception.appointments.CancelledBy;
import dev.reception.customers.Customer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * Response bodies for {@code /appointments/*}, written by hand rather than mapped from entities.
 *
 * <p>Every instant is rendered at the <strong>Business's</strong> offset and the envelope names the
 * zone, so a client never has to guess which clock a time is on and never consults the browser's
 * (ADR-0003, docs/04-api-overview.md §2).
 *
 * <p>{@code blockedFrom} and {@code blockedTo} are deliberately not on the wire. They are the
 * exclusion constraint's business, and a screen that showed them would be showing a customer ten
 * minutes of cleanup time as if it were part of their appointment.
 */
public final class AppointmentResponses {

    private AppointmentResponses() {}

    /** Money as a decimal string with its currency, never a float — see {@code ServiceResponses}. */
    public record Money(@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currency) {}

    /** Name and id only, matching the shape availability already returns for an Employee. */
    public record NamedRef(UUID id, String name) {}

    /**
     * The Customer as an Appointment shows them.
     *
     * <p>Phone and email are here because this is the dashboard: the business booked this person and
     * needs to reach them. No public response reuses this record (docs/06-security.md).
     */
    public record CustomerSummary(UUID id, String fullName, String phone, String email) {

        static CustomerSummary of(Customer customer) {
            return new CustomerSummary(customer.getId(), customer.fullName(), customer.phone(), customer.email());
        }
    }

    /**
     * @param price the snapshot taken at booking, not the Service's price today. The two differ the
     *     moment an owner changes a price, and this is the one that was agreed
     */
    public record AppointmentDetail(
            UUID id,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            AppointmentStatus status,
            NamedRef service,
            NamedRef employee,
            CustomerSummary customer,
            Money price,
            String confirmationCode,
            AppointmentSource source,
            String customerNote,
            OffsetDateTime cancelledAt,
            CancelledBy cancelledBy,
            String cancellationReason,
            Instant createdAt,
            Instant updatedAt) {

        static AppointmentDetail of(AppointmentQueryService.AppointmentView view, ZoneId zone) {
            Appointment appointment = view.appointment();
            return new AppointmentDetail(
                    appointment.getId(),
                    at(appointment.startsAt(), zone),
                    at(appointment.endsAt(), zone),
                    appointment.status(),
                    new NamedRef(appointment.serviceId(), view.serviceName()),
                    new NamedRef(appointment.employeeId(), view.employeeName()),
                    CustomerSummary.of(view.customer()),
                    new Money(appointment.priceAmount(), appointment.currency()),
                    appointment.confirmationCode(),
                    appointment.source(),
                    appointment.customerNote(),
                    at(appointment.cancelledAt(), zone),
                    appointment.cancelledBy(),
                    appointment.cancellationReason(),
                    appointment.createdAt(),
                    appointment.updatedAt());
        }
    }

    /** One Appointment plus its audit trail. Only the detail endpoint pays for the history. */
    public record AppointmentWithHistory(
            String timezone, AppointmentDetail appointment, List<HistoryEntry> history) {

        public static AppointmentWithHistory of(AppointmentQueryService.AppointmentDetailView detail, ZoneId zone) {
            return new AppointmentWithHistory(
                    zone.getId(),
                    AppointmentDetail.of(detail.view(), zone),
                    detail.history().stream().map(HistoryEntry::of).toList());
        }
    }

    /**
     * One recorded transition.
     *
     * <p>{@code payload} passes through as the {@code jsonb} it was stored as, rather than being
     * flattened into typed fields. Its shape genuinely differs per event type — a reschedule carries
     * two pairs of times, a cancellation carries a reason — and a record with every possible field
     * would be mostly null on every row.
     *
     * <p>{@code actorId} is null for a Customer and for the Receptionist, neither of which has a
     * user row; {@code actorType} is the field that always means something.
     */
    public record HistoryEntry(
            UUID id,
            AppointmentEventType type,
            ActorType actorType,
            UUID actorId,
            Map<String, Object> payload,
            Instant at) {

        static HistoryEntry of(AppointmentEvent event) {
            return new HistoryEntry(
                    event.getId(),
                    event.eventType(),
                    event.actorType(),
                    event.actorId(),
                    event.payload(),
                    event.createdAt());
        }
    }

    /**
     * A page of Appointments.
     *
     * <p>The pagination fields are spelled out rather than serialising Spring's {@code Page}, whose
     * JSON shape is an implementation detail that has changed between Spring versions and carries
     * more than a client needs (docs/04-api-overview.md §2).
     */
    public record AppointmentPage(
            String timezone,
            List<AppointmentDetail> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {

        public static AppointmentPage of(Page<AppointmentQueryService.AppointmentView> found, ZoneId zone) {
            return new AppointmentPage(
                    zone.getId(),
                    found.getContent().stream()
                            .map(view -> AppointmentDetail.of(view, zone))
                            .toList(),
                    found.getNumber(),
                    found.getSize(),
                    found.getTotalElements(),
                    found.getTotalPages());
        }
    }

    /** The booked Appointment, in the shape the detail endpoint returns minus the empty history. */
    public record BookedAppointment(String timezone, AppointmentDetail appointment) {

        public static BookedAppointment of(AppointmentQueryService.AppointmentDetailView detail, ZoneId zone) {
            return new BookedAppointment(zone.getId(), AppointmentDetail.of(detail.view(), zone));
        }
    }

    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }
}
