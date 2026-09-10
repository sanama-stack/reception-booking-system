package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.catalog.ServiceCatalogService;
import org.springframework.stereotype.Component;

/**
 * The bookable catalog: what this business does, how long each takes and what it costs.
 *
 * <p><strong>Active services only, and the model is given no way to ask otherwise.</strong> An
 * inactive Service is one the owner has withdrawn from sale; offering it would produce a
 * conversation that ends in {@code SERVICE_INACTIVE} at the moment of booking, after the customer
 * has already chosen a time.
 *
 * <p>Prices come from here and from {@code create_appointment}, and from nowhere else. That is the
 * structural half of "the model cannot write a discount" (docs/05-ai-architecture.md §7): there is
 * no arithmetic anywhere in the tool surface, so a number the model says is either one it was handed
 * or one it invented — and the second kind never survives to the appointment, whose price is
 * snapshotted by {@code BookingService} from the Service row.
 */
@Component
public class GetServicesTool implements Tool {

    private final ServiceCatalogService catalog;

    public GetServicesTool(ServiceCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "get_services";
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                name(),
                "Every service this business offers, with its duration and price. Call it when the "
                        + "customer asks what you do, what something costs, or how long it takes — and "
                        + "before booking, to turn what they described into a service_id.",
                ToolSchemas.object().build());
    }

    @Override
    public ObjectNode execute(JsonNode arguments, ToolContext context) {
        ObjectNode result = ToolResults.object();
        ArrayNode services = result.putArray("services");

        for (dev.reception.catalog.Service service : catalog.list(true)) {
            ObjectNode entry = services.addObject();
            entry.put("service_id", service.getId().toString());
            entry.put("name", service.name());
            entry.put("description", service.description());
            entry.put("duration_minutes", service.durationMinutes());
            // A string, not a number. A price crossing into JSON as a double is a price that can
            // come back as 49.989999999999995, and the model will read it aloud.
            entry.put("price", service.priceAmount().toPlainString());
            entry.put("currency", service.currency());
        }

        return result;
    }
}
