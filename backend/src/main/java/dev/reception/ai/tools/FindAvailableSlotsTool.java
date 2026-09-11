package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.domain.Slot;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The availability engine, reached by the model.
 *
 * <p><strong>The same {@link AvailabilityService} the dashboard's grid and the public booking page
 * call.</strong> No second implementation, no relaxation, no "close enough for a conversation" —
 * which is what makes the guarantee at the top of docs/05-ai-architecture.md true: a time the
 * Receptionist offers is a time the engine computed, and a time it invents fails re-validation at
 * booking.
 *
 * <p>Three things this tool does that the engine does not, all of them about the model rather than
 * about availability:
 *
 * <ul>
 *   <li><strong>It caps the range at fourteen days.</strong> The engine will happily answer for a
 *       longer one; a model that asks for three months gets a reply that crowds out the
 *       conversation. Silently narrowing would be worse than refusing, so the response says it was
 *       narrowed.
 *   <li><strong>It filters by time of day.</strong> "Anything after five" is a constraint the
 *       customer stated and the engine has no parameter for. Applying it here rather than letting
 *       the model discard slots itself keeps the count in {@code truncated} honest.
 *   <li><strong>It truncates the list.</strong> A fortnight of a busy salon is hundreds of slots and
 *       the model needs a handful to offer. {@code truncated} tells it there were more, so it says
 *       "among others" rather than presenting forty as the complete set.
 * </ul>
 */
@Component
public class FindAvailableSlotsTool implements Tool {

    /** Matches the engine's own ceiling, so a wider ask is refused here rather than deeper down. */
    static final int MAX_DAYS = 14;

    /**
     * Enough to offer a real choice across a few days, small enough that a busy fortnight does not
     * become the whole of the model's attention.
     */
    static final int MAX_SLOTS = 30;

    private final AvailabilityService availability;

    public FindAvailableSlotsTool(AvailabilityService availability) {
        this.availability = availability;
    }

    @Override
    public String name() {
        return "find_available_slots";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Free appointment times for a service. This is the ONLY source of times you may "
                        + "offer — never state or imply a time that did not come back from this call. "
                        + "Covers at most 14 days per call.",
                ToolSchemas.object()
                        .required(
                                "service_id",
                                "string",
                                "The service's id, from get_services — or from lookup_appointment when "
                                        + "moving an appointment that already exists.")
                        .required("date_from", "string", "First date to search, as YYYY-MM-DD in the business's own timezone.")
                        .optional("date_to", "string", "Last date to search, as YYYY-MM-DD. Null searches date_from alone.")
                        .optional("employee_id", "string", "Restrict to one staff member, by id. Null means anyone who can perform the service.")
                        .optional("earliest_time", "string", "Ignore slots starting before this 24-hour time, as HH:mm. Null means no lower bound.")
                        .optional("latest_time", "string", "Ignore slots starting after this 24-hour time, as HH:mm. Null means no upper bound.")
                        .build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        UUID serviceId = ToolArguments.uuid(arguments, "service_id");
        LocalDate from = ToolArguments.date(arguments, "date_from");
        LocalDate requestedTo = ToolArguments.optionalDate(arguments, "date_to");
        UUID employeeId = ToolArguments.optionalUuid(arguments, "employee_id");
        LocalTime earliest = ToolArguments.optionalTime(arguments, "earliest_time");
        LocalTime latest = ToolArguments.optionalTime(arguments, "latest_time");

        LocalDate to = requestedTo == null ? from : requestedTo;
        // A backwards range is a model mistake rather than an empty week. Answering "nothing free"
        // would send it looking for another time when the question itself was malformed.
        if (to.isBefore(from)) {
            to = from;
        }
        LocalDate capped = from.plusDays(MAX_DAYS - 1L);
        boolean rangeNarrowed = to.isAfter(capped);
        if (rangeNarrowed) {
            to = capped;
        }

        // Null: this is a new booking, so nothing is excluded. The reschedule path is the only
        // caller anywhere that passes an exclusion, and it passes an appointment it already proved
        // (PublicAppointmentController.manageAvailability).
        AvailabilityService.Availability found = availability.find(serviceId, from, to, employeeId, null);
        ZoneId zone = found.timezone();

        ObjectNode result = ToolResults.object();
        result.put("searched_from", from.toString());
        result.put("searched_to", to.toString());
        if (rangeNarrowed) {
            result.put("range_narrowed_to_days", MAX_DAYS);
        }

        ArrayNode slots = result.putArray("slots");
        int matched = 0;
        for (Slot.Day day : found.result().days()) {
            for (Slot slot : day.slots()) {
                LocalTime startsAt = slot.startsAt().atZone(zone).toLocalTime();
                if (earliest != null && startsAt.isBefore(earliest)) {
                    continue;
                }
                if (latest != null && startsAt.isAfter(latest)) {
                    continue;
                }
                matched++;
                if (slots.size() >= MAX_SLOTS) {
                    continue;
                }
                ObjectNode entry = slots.addObject();
                // With the offset, because create_appointment takes this value back verbatim and an
                // offsetless local time is ambiguous across a DST boundary (ADR-0003).
                entry.put("starts_at", slot.startsAt().atZone(zone).toOffsetDateTime().toString());
                entry.put("ends_at", slot.endsAt().atZone(zone).toOffsetDateTime().toString());
                entry.put("employee_id", slot.employeeId().toString());
                entry.put("employee_name", slot.employeeName());
            }
        }

        result.put("truncated", matched > slots.size());

        // The engine's reason, not one composed here. "Closed that day" and "fully booked" send a
        // customer to different next questions, and the engine is the only thing that knows which
        // it was.
        if (slots.isEmpty()) {
            result.put(
                    "empty_reason",
                    found.result().emptyReason() == null
                            // Non-null only when the engine found slots and the time filters above
                            // then removed every one of them — a distinction the engine cannot make
                            // because it never saw the customer's "after five".
                            ? "OUTSIDE_REQUESTED_TIMES"
                            : found.result().emptyReason().name());
        }

        return result;
    }
}
