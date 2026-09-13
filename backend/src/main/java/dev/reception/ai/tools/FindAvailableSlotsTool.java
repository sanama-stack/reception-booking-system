package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.scheduling.application.AvailabilityService;
import dev.reception.scheduling.domain.EmptyReason;
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
 *   <li><strong>It caps the range at {@value #MAX_DAYS} days.</strong> The engine will happily
 *       answer for a longer one — its own ceiling is {@value AvailabilityService#MAX_RANGE_DAYS}
 *       and it refuses rather than narrows, which
 *       is a different bound for a different reason ({@link #MAX_DAYS}). A model that asks for
 *       three months gets a reply that crowds out the conversation. Silently narrowing would be
 *       worse than refusing, so the response says it was narrowed.
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

    /**
     * The widest range one call may cover, so that a fortnight of a busy salon does not become the
     * whole of the model's context.
     *
     * <p><strong>This is not the engine's ceiling.</strong> That is
     * {@link AvailabilityService#MAX_RANGE_DAYS}, and it is
     * {@value AvailabilityService#MAX_RANGE_DAYS}. This javadoc used to say
     * <em>"matches the engine's own ceiling, so a wider ask is refused here rather than deeper
     * down"</em>, which was wrong three ways over — and wrong four lines below a class javadoc that
     * says the true thing, that "the engine will happily answer for a longer one".
     *
     * <p>The two bounds differ in kind, not only in size. The engine's is a <em>cost</em> control —
     * the query is Employees × days × grid (docs/01-prd.md FR-5) — and it <strong>refuses</strong>,
     * with a 400. This one is a <em>context</em> budget, and it <strong>narrows</strong>, reporting
     * {@code range_narrowed_to_days} so the model is told a smaller question was answered.
     *
     * <p>It happens to be the stricter of the two, so the engine never sees a range it would reject
     * from this path. That is a consequence of the number, not a reason for it: were the context
     * budget ever raised above {@value AvailabilityService#MAX_RANGE_DAYS}, the engine would start
     * refusing and this tool would need to say
     * so rather than silently inheriting a bound it does not describe.
     */
    static final int MAX_DAYS = 14;

    /**
     * Enough to offer a real choice across a few days, small enough that a busy fortnight does not
     * become the whole of the model's attention.
     */
    static final int MAX_SLOTS = 30;

    /**
     * The one value {@code empty_reason} can take that {@link EmptyReason} does not contain.
     *
     * <p><strong>Deliberately not a fifth constant on that enum.</strong> {@code EmptyReason}'s
     * stated contract is that the order of its constants is the order the engine tries them and
     * that exactly one comes back. This reason is not produced by the engine at all — it is
     * produced here, out of a time-of-day filter the engine has no parameter for — so adding it
     * there would make that sentence false for the sake of tidiness.
     *
     * <p><strong>It rests on an invariant held somewhere else.</strong> {@code
     * AvailabilityResult.of} is the only way to build a result, and it sets {@code emptyReason} to
     * null exactly when at least one Slot was found. That is what makes a null reason beside an
     * empty list mean "the engine found times and the filter below removed all of them" rather than
     * "the engine said nothing". If that invariant ever moved, this line would explain the engine's
     * silence with a cause that did not happen — confidently, to a model that will repeat it to a
     * Customer.
     */
    static final String OUTSIDE_REQUESTED_TIMES = "OUTSIDE_REQUESTED_TIMES";

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
                        + "Covers at most " + MAX_DAYS + " days per call.",
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
                            // Null only when the engine found slots and the time filters above
                            // then removed every one of them — a distinction the engine cannot make
                            // because it never saw the customer's "after five".
                            ? OUTSIDE_REQUESTED_TIMES
                            : found.result().emptyReason().name());
        }

        return result;
    }
}
