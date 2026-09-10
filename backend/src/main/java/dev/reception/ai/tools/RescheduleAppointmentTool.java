package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.RescheduleService;
import dev.reception.business.BusinessService;
import dev.reception.customers.CustomerService;
import dev.reception.staff.EmployeeService;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Moving an Appointment the conversation has proven, to a time the engine has offered.
 *
 * <p>Guarded identically to {@link CancelAppointmentTool} and for the same reason, and bound by the
 * Cancellation Window through the same predicate — {@code RescheduleService} asks
 * {@code Actor#boundByCancellationWindow}, which phase 09 introduced precisely because this tool
 * would otherwise have been able to move an appointment a Customer was refused permission to move.
 *
 * <p><strong>The Service is not a parameter.</strong> A Customer rescheduling is moving what they
 * booked, not choosing again — the same rule {@code PublicAppointmentController.manageAvailability}
 * encodes. Changing to a different service is a cancellation and a new booking, which are two tools
 * the Receptionist already has and two things the customer should be asked about separately.
 *
 * <p><strong>Availability for a move must exclude the appointment being moved</strong>, or a 10:00
 * booking cannot be shifted to 10:15 because it blocks itself. {@code RescheduleService} passes that
 * exclusion internally, so this tool inherits it — but a model calling
 * {@code find_available_slots} to pick the new time does <em>not</em>, and will therefore not be
 * offered times overlapping the current booking. The system prompt tells it to offer what it found
 * and let this call refuse anything else, which is the honest division: the engine decides, the
 * model asks.
 */
@Component
public class RescheduleAppointmentTool implements Tool {

    private final RescheduleService reschedule;
    private final EmployeeService employees;
    private final BusinessService businesses;
    private final CustomerService customers;

    public RescheduleAppointmentTool(
            RescheduleService reschedule,
            EmployeeService employees,
            BusinessService businesses,
            CustomerService customers) {
        this.reschedule = reschedule;
        this.employees = employees;
        this.businesses = businesses;
        this.customers = customers;
    }

    @Override
    public String name() {
        return "reschedule_appointment";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Move an appointment to a different time. You may only move one that "
                        + "create_appointment or lookup_appointment returned earlier in THIS "
                        + "conversation. Find the new time with find_available_slots first; the "
                        + "service stays the same.",
                ToolSchemas.object()
                        .required("appointment_id", "string", "The id from create_appointment or lookup_appointment.")
                        .required("new_starts_at", "string", "The new slot's starts_at, copied exactly as find_available_slots returned it, including the timezone offset.")
                        .optional("employee_id", "string", "A different staff member, by id. Null keeps whoever is on the appointment now.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        UUID appointmentId = ToolArguments.uuid(arguments, "appointment_id");
        if (!context.authorized().contains(appointmentId)) {
            return WriteToolAuthorization.refuse();
        }

        Appointment moved = reschedule.reschedule(
                appointmentId,
                ToolArguments.instant(arguments, "new_starts_at").toInstant(),
                ToolArguments.optionalUuid(arguments, "employee_id"),
                Actor.ai());

        ZoneId zone = businesses.read().timezone();
        ObjectNode result = ToolResults.object();
        result.put("appointment_id", moved.getId().toString());
        result.put("starts_at", moved.startsAt().atZone(zone).toOffsetDateTime().toString());
        result.put("ends_at", moved.endsAt().atZone(zone).toOffsetDateTime().toString());
        result.put("employee_name", employees.read(moved.employeeId()).fullName());
        result.put("reschedule_email_sent", customers.read(moved.customerId()).hasEmail());
        return result;
    }
}
