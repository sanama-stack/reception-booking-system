package dev.reception.ai.port;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * A tool as the model sees it: a name, a description it reads, and a JSON Schema for its arguments.
 *
 * <p>The schema is <strong>strict</strong> — {@code additionalProperties: false}, every property
 * listed as required, optional ones typed as nullable. Strict schemas remove malformed-argument
 * handling from the runtime instead of validating defensively afterwards
 * (docs/05-ai-architecture.md §3).
 *
 * <p>The schema is carried as a {@code ObjectNode} rather than a string so it can be inspected. That
 * is not a convenience: {@code ToolSchemaTest} walks these to assert that no published schema
 * anywhere contains a {@code business_id} property, which is the enforcement behind ADR-0004's
 * central claim. A schema stored as text could only be grepped.
 */
public record ToolSpec(String name, String description, ObjectNode parameters) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parameters, "parameters");
    }
}
