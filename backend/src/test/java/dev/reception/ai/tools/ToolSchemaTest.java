package dev.reception.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What the model is allowed to be told it can ask for.
 *
 * <p>These assertions are the enforcement behind ADR-0004's central claim. That document says
 * cross-tenant access is "not merely blocked — it is inexpressible", and the only thing that can
 * make that true is the absence of a place to put a tenant in any published schema. A prose
 * statement in an ADR does not survive a ninth tool written in a hurry; this test does.
 *
 * <p>Run against the {@link ToolRegistry} the application actually builds, not against a list
 * restated here. A tool that Spring finds and this test does not would be the exact hole worth
 * worrying about.
 */
class ToolSchemaTest extends IntegrationTest {

    /**
     * The eight of docs/05-ai-architecture.md §3, plus {@code resolve_date} (#17).
     *
     * <p>Asserted by name and by count, so both halves of a mistake are caught: a tool quietly
     * removed, and a ninth quietly added. The second is the one that matters — a new capability
     * reaching the Receptionist without anybody deciding it should is how a tool surface stops being
     * a designed thing.
     */
    private static final List<String> EXPECTED_TOOLS = List.of(
            "get_business_info",
            "get_services",
            "get_service_details",
            "find_available_slots",
            "create_appointment",
            "lookup_appointment",
            "cancel_appointment",
            "reschedule_appointment",
            // The ninth, and it went through this test rather than around it -- which is what the
            // count assertion is for. It reads nothing and writes nothing: it turns a weekday into
            // a date, because the model doing that arithmetic is #17.
            "resolve_date");

    @Autowired
    private ToolRegistry registry;

    @Test
    @DisplayName("the registry publishes exactly the nine tools the design names")
    void exactly_nine_tools_are_published() {
        assertThat(registry.specs().stream().map(ToolSpec::name))
                .containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
    }

    /**
     * <strong>The isolation property.</strong>
     *
     * <p>Walks every schema to its leaves rather than checking the top level, because a nested
     * object would hide one just as well — and strict mode does not forbid nesting.
     */
    @Test
    @DisplayName("no published schema contains a business_id, anywhere, at any depth")
    void no_tool_accepts_a_tenant_identifier() {
        List<String> offenders = new ArrayList<>();

        for (ToolSpec spec : registry.specs()) {
            for (String property : propertyNamesIn(spec.parameters())) {
                String normalised = property.toLowerCase(java.util.Locale.ROOT).replace("_", "");
                // Both spellings, because the schemas are snake_case and the Java is camelCase, and
                // whichever one a future tool used would be equally fatal.
                if (normalised.equals("businessid")
                        || normalised.equals("tenantid")
                        || normalised.equals("businessslug")
                        || normalised.equals("slug")) {
                    offenders.add(spec.name() + "." + property);
                }
            }
        }

        assertThat(offenders)
                .as("A tool that takes a tenant makes cross-tenant access expressible, which ADR-0004 "
                        + "exists to prevent. The tenant comes from the conversation row.")
                .isEmpty();
    }

    /**
     * Strict mode's three requirements, on every schema.
     *
     * <p>Checked because the provider rejects a request whose schema is not strict-compatible, and
     * that failure arrives as a 400 with the whole conversation in it — at runtime, in front of a
     * customer, rather than here.
     */
    @Test
    @DisplayName("every schema satisfies strict mode: closed, and every property required")
    void every_schema_is_strict() {
        for (ToolSpec spec : registry.specs()) {
            JsonNode schema = spec.parameters();

            assertThat(schema.path("type").asText())
                    .as("%s: schema root must be an object", spec.name())
                    .isEqualTo("object");

            assertThat(schema.path("additionalProperties").asBoolean(true))
                    .as("%s: additionalProperties must be false", spec.name())
                    .isFalse();

            List<String> declared = new ArrayList<>();
            schema.path("properties").fieldNames().forEachRemaining(declared::add);

            List<String> required = new ArrayList<>();
            schema.path("required").forEach(node -> required.add(node.asText()));

            // Every property, including the nullable ones. Under strict mode "optional" is a
            // nullable type, never an absent key — the line in ToolSchemas most likely to be
            // "corrected" by someone reading required in its ordinary sense.
            assertThat(required)
                    .as("%s: every property must be listed as required under strict mode", spec.name())
                    .containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    /** Every tool tells the model what it is for. An undescribed tool is one it will misuse. */
    @Test
    void every_tool_has_a_description() {
        for (ToolSpec spec : registry.specs()) {
            assertThat(spec.description())
                    .as("%s has no description", spec.name())
                    .isNotBlank();
        }
    }

    private static List<String> propertyNamesIn(JsonNode schema) {
        List<String> names = new ArrayList<>();
        collect(schema, names);
        return names;
    }

    private static void collect(JsonNode node, List<String> names) {
        JsonNode properties = node.path("properties");
        properties.fieldNames().forEachRemaining(name -> {
            names.add(name);
            collect(properties.path(name), names);
        });
        // Array item schemas, which are where a nested object would otherwise escape the walk.
        if (node.has("items")) {
            collect(node.path("items"), names);
        }
    }
}
