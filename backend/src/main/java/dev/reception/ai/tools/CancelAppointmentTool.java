package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.appointments.Actor;
import dev.reception.appointments.Appointment;
import dev.reception.appointments.CancellationService;
import dev.reception.customers.CustomerService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cancelling — the first of the two tools that may only touch an Appointment this conversation has
 * proven.
 *
 * <p><strong>The guard runs before the application service, not inside it.</strong> That ordering is
 * the substance of ADR-0004: an appointment id the model produced from nowhere is refused by a set
 * membership test, so the service never sees it, no row is read, and nothing about whether that id
 * exists comes back. "I do not have an appointment I can cancel" is the same sentence for a
 * hallucinated uuid, another customer's real one, and another business's.
 *
 * <p><strong>The Cancellation Window binds this.</strong> {@link Actor#ai()} is the customer's side
 * ({@code Actor#asCancellingParty}), so a customer who is too late to cancel at
 * {@code /manage/{token}} is too late to cancel by talking to the Receptionist. Any other
 * arrangement would make the chat panel a way around a deadline the owner set.
 */
@Component
public class CancelAppointmentTool implements Tool {

    private final CancellationService cancellation;
    private final CustomerService customers;

    public CancelAppointmentTool(CancellationService cancellation, CustomerService customers) {
        this.cancellation = cancellation;
        this.customers = customers;
    }

    @Override
    public String name() {
        return "cancel_appointment";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Cancel an appointment. You may only cancel one that create_appointment or "
                        + "lookup_appointment returned earlier in THIS conversation. Confirm with the "
                        + "customer before calling — cancelling cannot be undone.",
                ToolSchemas.object()
                        .required("appointment_id", "string", "The id from create_appointment or lookup_appointment.")
                        .optional("reason", "string", "Why they are cancelling, if they said. Null if they did not.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        UUID appointmentId = ToolArguments.uuid(arguments, "appointment_id");
        if (!context.authorized().contains(appointmentId)) {
            return WriteToolAuthorization.refuse();
        }

        Appointment cancelled = cancellation.cancel(appointmentId, Actor.ai(), ToolArguments.optionalText(arguments, "reason"));

        ObjectNode result = ToolResults.object();
        result.put("appointment_id", cancelled.getId().toString());
        result.put("status", cancelled.status().name());
        // ADR-0008's question, asked of the Receptionist. A cancellation email is enqueued only if
        // the Customer has an address on file, and the model would otherwise say "you'll get a
        // confirmation" because that is what a receptionist says.
        result.put("cancellation_email_sent", customers.read(cancelled.customerId()).hasEmail());
        return result;
    }
}
