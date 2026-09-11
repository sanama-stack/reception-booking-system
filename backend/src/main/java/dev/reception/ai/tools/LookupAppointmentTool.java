package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.AppointmentDirectory;
import dev.reception.appointments.AppointmentLookup;
import dev.reception.appointments.ConfirmationCodeLookup;
import dev.reception.business.BusinessService;
import dev.reception.catalog.ServiceCatalogService;
import dev.reception.staff.EmployeeService;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * How a returning customer proves, in conversation, that an Appointment is theirs.
 *
 * <p>One of exactly two tools that may add to {@link AuthorizedAppointments}, and the only one a
 * customer who booked yesterday can reach. Everything the Receptionist is later allowed to cancel or
 * move passed through here or through {@code create_appointment} in this same conversation
 * (ADR-0004).
 *
 * <p><strong>Confined to this conversation's Business, which the public endpoint is not.</strong>
 * That difference is the point of {@link ConfirmationCodeLookup#withinBusiness}. The public lookup
 * has no tenant and adopts whichever one the code names — correct there, because a Customer
 * following a link from an email never said which business they meant. Here they did: they are
 * talking to one business's Receptionist. Reusing the adopting version would have let a customer
 * hand business A's chat a code belonging to business B and then cancel B's appointment through A —
 * a cross-tenant path opened by the one tool whose whole job is proving ownership.
 *
 * <p><strong>Both values, never either.</strong> A phone number alone would let anyone who knows one
 * reach that person's appointments (docs/06-security.md §6). The code alone is eight characters;
 * what bounds guessing it here is the conversation's own message ceiling and the chat rate limits,
 * which is why those are enforced server-side rather than asked of the model.
 */
@Component
public class LookupAppointmentTool implements Tool {

    private final ConfirmationCodeLookup codes;
    private final AppointmentLookup appointments;
    private final ServiceCatalogService catalog;
    private final EmployeeService employees;
    private final BusinessService businesses;

    public LookupAppointmentTool(
            ConfirmationCodeLookup codes,
            AppointmentLookup appointments,
            ServiceCatalogService catalog,
            EmployeeService employees,
            BusinessService businesses) {
        this.codes = codes;
        this.appointments = appointments;
        this.catalog = catalog;
        this.employees = employees;
        this.businesses = businesses;
    }

    @Override
    public String name() {
        return "lookup_appointment";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Find an existing appointment from its confirmation code and the phone number it "
                        + "was booked with. You MUST call this before you can cancel or move an "
                        + "appointment the customer did not book earlier in this conversation. Ask "
                        + "them for both values; never guess either.",
                ToolSchemas.object()
                        .required("confirmation_code", "string", "The code from the customer's confirmation email.")
                        .required("phone", "string", "The phone number the appointment was booked with, as the customer says it.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        String code = ToolArguments.text(arguments, "confirmation_code");
        String phone = ToolArguments.text(arguments, "phone");

        Optional<AppointmentDirectory.ConfirmationCodeMatch> match =
                codes.withinBusiness(context.businessId(), code, phone);
        if (match.isEmpty()) {
            // The same answer for a wrong code, a wrong number, and a code that is real at another
            // business. Distinguishing them would answer the question an attacker is asking.
            return ToolResults.error(
                    "NOT_FOUND", "I could not find an appointment with that code and phone number.");
        }

        // Tenant-scoped, and the tenant was never adopted from the candidate — it is the
        // conversation's, and the filter above is what guarantees the row is inside it.
        Appointment appointment = appointments.require(match.get().getAppointmentId());

        // THE AUTHORISATION. Below the read, because until it succeeded there was nothing proven.
        context.authorized().authorize(appointment.getId());

        ZoneId zone = businesses.read().timezone();
        ObjectNode result = ToolResults.object();
        result.put("appointment_id", appointment.getId().toString());
        result.put("confirmation_code", appointment.confirmationCode());
        result.put("starts_at", appointment.startsAt().atZone(zone).toOffsetDateTime().toString());
        result.put("ends_at", appointment.endsAt().atZone(zone).toOffsetDateTime().toString());
        // The ids travel beside the names, because the next tool needs the id and the customer
        // needs the name. Returning the name alone made the model recover the id from get_services
        // by matching on it — an inference over a catalog a Business may fill with "Colour" and
        // "Colour (long)", where picking the wrong one books the wrong duration and looks exactly
        // like picking the right one (#26).
        result.put("service_id", appointment.serviceId().toString());
        result.put("service_name", catalog.read(appointment.serviceId()).name());
        result.put("employee_id", appointment.employeeId().toString());
        result.put("employee_name", employees.read(appointment.employeeId()).fullName());
        result.put("status", appointment.status().name());
        return result;
    }
}
