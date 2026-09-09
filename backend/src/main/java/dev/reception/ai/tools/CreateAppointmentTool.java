package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentSource;
import dev.reception.appointments.BookingService;
import dev.reception.business.BusinessService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.customers.CustomerFieldNames;
import dev.reception.customers.CustomerService;
import dev.reception.staff.EmployeeService;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Booking, by conversation.
 *
 * <p><strong>The same {@link BookingService} the dashboard and the Classic Flow call.</strong>
 * Everything that makes a booking safe comes with it and none of it is re-implemented here: the
 * exclusion constraint, the availability re-check, the price snapshot, the Confirmation Code, the
 * audit event and the confirmation email. This class adds a source, an actor and a set of field
 * names, exactly as {@code PublicBookingController} does — which is the whole of ADR-0004's claim
 * that the Receptionist adds no capability the Classic Flow lacks.
 *
 * <p><strong>The re-check is what makes a fabricated time harmless.</strong> A model that invents a
 * start rather than quoting one {@code find_available_slots} returned reaches
 * {@code availability.reasonNotBookable} like any other caller and is refused — with a reason the
 * customer can act on, not a silent success. This is the first of the five hallucination controls
 * and the only one that operates after the model has already gone wrong.
 *
 * <p>{@code AppointmentSource.AI} is set here and could not have been sent. A body that named its
 * own source would let the Classic Flow claim to be the Receptionist, or the reverse — and
 * "the Receptionist booked this" is the first thing anybody wants to know when a customer says a
 * booking is wrong.
 */
@Component
public class CreateAppointmentTool implements Tool {

    /**
     * The Tool argument names, so a validation failure comes back under the name the model sent.
     *
     * <p>The fourth entry point into {@code CustomerService}, and the reason
     * {@link CustomerFieldNames} takes the names as an argument rather than guessing them. The
     * audience is {@code CUSTOMER}: the person who will hear this refusal read aloud is a customer,
     * so advice about a Settings screen they cannot reach must not reach them — the defect phase 08
     * shipped and then fixed.
     */
    private static final CustomerFieldNames TOOL_FIELDS = new CustomerFieldNames(
            "customer_name", "customer_phone", "customer_email", dev.reception.common.phone.PhoneAudience.CUSTOMER);

    private final BookingService booking;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final BusinessService businesses;
    private final CustomerService customers;

    public CreateAppointmentTool(
            BookingService booking,
            ServiceCatalogService catalog,
            EmployeeService employees,
            BusinessService businesses,
            CustomerService customers) {
        this.booking = booking;
        this.catalog = catalog;
        this.employees = employees;
        this.businesses = businesses;
        this.customers = customers;
    }

    @Override
    public String name() {
        return "create_appointment";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Book an appointment. Only call this once you have the customer's name, phone "
                        + "number and a starts_at that find_available_slots actually returned. Do NOT "
                        + "tell the customer they are booked until this call has come back "
                        + "successfully — if it returns an error, nothing was booked.",
                ToolSchemas.object()
                        .required("service_id", "string", "The service's id, from get_services.")
                        .required("employee_id", "string", "The staff member's id, from the chosen slot.")
                        .required("starts_at", "string", "The slot's starts_at, copied exactly as find_available_slots returned it, including the timezone offset.")
                        .required("customer_name", "string", "The customer's full name, as they gave it.")
                        .required("customer_phone", "string", "The customer's phone number, as they gave it.")
                        .optional("customer_email", "string", "The customer's email address. Null if they did not give one — do not invent one, and tell them no confirmation email will be sent.")
                        .optional("note", "string", "Anything the customer asked you to pass on. Null if there is nothing.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        UUID serviceId = ToolArguments.uuid(arguments, "service_id");
        UUID employeeId = ToolArguments.uuid(arguments, "employee_id");

        Appointment booked = booking.book(
                new BookingService.BookingRequest(
                        serviceId,
                        employeeId,
                        ToolArguments.instant(arguments, "starts_at").toInstant(),
                        ToolArguments.text(arguments, "customer_name"),
                        ToolArguments.text(arguments, "customer_phone"),
                        ToolArguments.optionalText(arguments, "customer_email"),
                        ToolArguments.optionalText(arguments, "note"),
                        TOOL_FIELDS),
                AppointmentSource.AI,
                // Not Actor.customer(): a booking is not bound by the Cancellation Window, and
                // recording that the Receptionist made this one is what the source and the audit
                // event are for. See Actor#asCancellingParty for why cancelling differs.
                Actor.ai());

        // THE AUTHORISATION, and the reason the customer can then say "actually, make it Thursday"
        // without being asked for a Confirmation Code they have not received yet.
        context.authorized().authorize(booked.getId());

        ZoneId zone = businesses.read().timezone();
        ObjectNode result = ToolResults.object();
        result.put("appointment_id", booked.getId().toString());
        // The Confirmation Code the email will carry. Said out loud by the Receptionist because the
        // email may be a minute away, and because there may be no email at all — the customer needs
        // some way back to this appointment either way (ADR-0007).
        result.put("confirmation_code", booked.confirmationCode());
        result.put("starts_at", booked.startsAt().atZone(zone).toOffsetDateTime().toString());
        result.put("ends_at", booked.endsAt().atZone(zone).toOffsetDateTime().toString());
        result.put("service_name", catalog.read(booked.serviceId()).name());
        result.put("employee_name", employees.read(booked.employeeId()).fullName());
        result.put("price", booked.priceAmount().toPlainString());
        result.put("currency", booked.currency());

        // ADR-0007, reaching the Receptionist. The screen version of this cost phase 08 two
        // sessions to get right, and the failure was the same one a chatbot makes most easily:
        // promising an email that was never enqueued. The Receptionist is told the answer rather
        // than left to infer it from whether an address was in its arguments, because the two
        // disagree — a returning phone number keeps the address already on file, so a typed address
        // is not evidence anything will be sent, and a stored one means a message goes out even
        // though this turn never saw an address.
        //
        // The same predicate NotificationEnqueuer gates on, asked of the resolved Customer. One
        // definition of "reachable by email", now four callers.
        result.put("confirmation_email_sent", customers.read(booked.customerId()).hasEmail());
        return result;
    }
}
