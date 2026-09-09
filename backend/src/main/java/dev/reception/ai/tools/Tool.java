package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;

/**
 * One capability the Receptionist has, and the only kind of thing it has.
 *
 * <p>Eight implementations, each of which calls an application service the Classic Flow or the
 * dashboard already proved correct. A tool does three things: publish a schema, translate the
 * model's arguments into that service's parameters, and translate the answer back into JSON. It
 * contains no business rule, because a rule implemented here would be a second implementation of
 * one that already exists — and the one that already exists is the one the database enforces.
 *
 * <p>Tools do not handle their own domain failures. {@link ToolRegistry} converts an
 * {@code ApiException} into a structured result centrally, so eight classes do not each get to
 * decide what {@code SLOT_UNAVAILABLE} reads like.
 */
public interface Tool {

    /** The name the model calls, in {@code snake_case} as the schemas are written. */
    String name();

    /** What the model is told this tool does and when to reach for it. */
    ToolSpec spec();

    /**
     * Runs it.
     *
     * @param arguments validated against {@link #spec()} by the provider's strict mode before it
     *     ever reaches here, and re-validated by the application service afterwards, which does not
     *     know an AI called it
     */
    ObjectNode execute(JsonNode arguments, ToolContext context);
}
