package dev.reception.ai.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One tool invocation a model asked for.
 *
 * <p>{@code arguments} is the parsed JSON the model produced, not a domain object: this record
 * crosses the port boundary and the port knows nothing about what any particular tool takes. The
 * registry is where a name becomes a tool and arguments become typed values.
 *
 * @param id the provider's identifier for this call, which the tool result must quote back so the
 *     model can match answer to question. Opaque — never parsed, only echoed
 */
public record ToolCall(String id, String name, JsonNode arguments) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
    }
}
