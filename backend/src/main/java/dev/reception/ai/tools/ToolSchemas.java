package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the strict JSON Schemas the eight tools publish.
 *
 * <p>Strict mode has three requirements and it is easy to satisfy two of them: every property must
 * appear in {@code required}, {@code additionalProperties} must be {@code false}, and an optional
 * argument is therefore expressed as a <em>nullable type</em> rather than as an absent key. This
 * builder makes that the only reachable shape — {@link Builder#optional} adds the property to
 * {@code required} exactly as {@link Builder#required} does, and differs only in typing it
 * {@code ["string", "null"]}.
 *
 * <p>Writing the schemas by hand instead of deriving them from Java records is deliberate. The
 * description strings are the model's documentation and are the highest-leverage text in the phase;
 * a generated schema would put them in annotations, where they are read by nobody and drift
 * silently from the behaviour they describe.
 */
public final class ToolSchemas {

    private ToolSchemas() {}

    public static Builder object() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<String, ObjectNode> properties = new LinkedHashMap<>();

        private Builder() {}

        /** A property the model must supply a real value for. */
        public Builder required(String name, String type, String description) {
            ObjectNode property = JsonNodeFactory.instance.objectNode();
            property.put("type", type);
            property.put("description", description);
            properties.put(name, property);
            return this;
        }

        /**
         * A property the model may leave empty — typed nullable, still listed as required.
         *
         * <p>The description should say what {@code null} means, because to the model that is a
         * value it is choosing rather than a field it is omitting.
         */
        public Builder optional(String name, String type, String description) {
            ObjectNode property = JsonNodeFactory.instance.objectNode();
            ArrayNode types = property.putArray("type");
            types.add(type);
            types.add("null");
            property.put("description", description);
            properties.put(name, property);
            return this;
        }

        public ObjectNode build() {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("type", "object");

            ObjectNode props = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            properties.forEach((name, property) -> {
                props.set(name, property);
                // Every property, including the nullable ones. This is what "strict" means and it
                // is the line most likely to be "corrected" by someone who reads required as
                // meaning mandatory in the ordinary sense.
                required.add(name);
            });

            schema.put("additionalProperties", false);
            return schema;
        }
    }
}
