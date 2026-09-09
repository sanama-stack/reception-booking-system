package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.business.Business;
import dev.reception.business.BusinessClosure;
import dev.reception.business.BusinessHours;
import dev.reception.business.BusinessHoursService;
import dev.reception.business.BusinessService;
import dev.reception.business.ClosureService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Address, contact details, hours, closures and policies — the facts a customer asks for by name.
 *
 * <p>Takes no arguments at all, which is worth stating: there is nothing to select, because the
 * only business this conversation can see is the one its tenant already names. A schema with a
 * business identifier is the thing ADR-0004 makes inexpressible, and a schema with a "which fields"
 * parameter would only give the model a way to ask a narrower question badly.
 *
 * <p>Overlaps the system prompt on purpose. The prompt already carries this material, so most turns
 * never call this — but a long conversation slides the prompt's facts out of the model's attention
 * long before it slides them out of the window, and a tool the model can reach for is cheaper than
 * a wrong answer.
 */
@Component
public class GetBusinessInfoTool implements Tool {

    private final BusinessService businesses;
    private final BusinessHoursService hours;
    private final ClosureService closures;

    public GetBusinessInfoTool(BusinessService businesses, BusinessHoursService hours, ClosureService closures) {
        this.businesses = businesses;
        this.hours = hours;
        this.closures = closures;
    }

    @Override
    public String name() {
        return "get_business_info";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Address, phone, opening hours, upcoming closures and the cancellation policy for "
                        + "this business. Call it whenever the customer asks where you are, when you "
                        + "are open, or what happens if they need to cancel.",
                ToolSchemas.object().build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        Business business = businesses.read();
        ZoneId zone = business.timezone();

        ObjectNode result = ToolResults.object();
        result.put("name", business.name());
        result.put("description", business.description());
        result.put("address", business.addressLine());
        result.put("city", business.city());
        result.put("country", business.country());
        result.put("phone", business.phone());
        result.put("email", business.email());
        result.put("website", business.website());
        result.put("timezone", zone.getId());
        result.put("currency", business.currency());
        // The model has no clock. Every relative date it resolves — "tomorrow", "next Tuesday" —
        // resolves against this, so it is the single most load-bearing field in the tool.
        result.put("today", LocalDate.now(context.clock().withZone(zone)).toString());

        ArrayNode week = result.putArray("opening_hours");
        for (BusinessHours interval : hours.read()) {
            ObjectNode day = week.addObject();
            day.put("day", interval.dayOfWeek().toString());
            day.put("opens_at", interval.opensAt().toString());
            day.put("closes_at", interval.closesAt().toString());
        }

        // Closures are rendered as dates in business time rather than as instants. A customer
        // asking "are you open on the 24th" is asking about a date on a wall calendar.
        ArrayNode upcoming = result.putArray("closures");
        List<BusinessClosure> all = closures.list();
        for (BusinessClosure closure : all) {
            ObjectNode entry = upcoming.addObject();
            entry.put("from", LocalDate.ofInstant(closure.startsAt(), zone).toString());
            entry.put("to", LocalDate.ofInstant(closure.endsAt(), zone).toString());
            entry.put("reason", closure.reason());
        }

        ObjectNode policies = result.putObject("policies");
        policies.put("cancellation_policy", business.cancellationPolicy());
        policies.put("cancellation_window_hours", business.cancellationWindowHours());
        policies.put("minimum_notice_minutes", business.minLeadTimeMinutes());
        policies.put("books_up_to_days_ahead", business.maxAdvanceDays());

        return result;
    }
}
