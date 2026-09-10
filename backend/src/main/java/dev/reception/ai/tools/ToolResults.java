package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.common.error.FieldError;
import java.util.List;

/**
 * How a tool answers.
 *
 * <p>Two shapes and no third: an object of facts, or an object with an {@code error} and a
 * {@code message}. The model is told in the system prompt that an {@code error} means the thing did
 * not happen, which is the only interpretation available to it — there is no partial success in any
 * of the eight.
 *
 * <p><strong>A refusal may also carry {@code fields}</strong>, which is not a third shape but the
 * detail of the second. {@code VALIDATION_FAILED}'s message is the generic "One or more fields are
 * invalid", so without the per-field entries the model learns that something is wrong and never
 * which thing — and a model that cannot tell which argument was rejected retries the one it already
 * sent. Observed: a customer gave an unusable phone number, and the identical payload went out three
 * times before anybody asked them for a different one.
 *
 * <p>Each entry names one argument of the call that failed, because the tools pass their own
 * argument names into validation rather than the HTTP body's — see {@code CustomerFieldNames}. So
 * {@code field} is a key the model itself wrote, and can correct.
 *
 * <p>The message is written for the customer to hear, because the model will paraphrase it to them.
 * It never contains an id, a stack frame or a class name.
 */
public final class ToolResults {

    private ToolResults() {}

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * A refusal the model should explain and work around.
     *
     * @param code the {@code ErrorCode} name where a domain failure produced this, so a transcript
     *     can be matched against the same vocabulary every other surface uses
     */
    public static ObjectNode error(String code, String message) {
        return error(code, message, List.of());
    }

    /**
     * A refusal that says which arguments were rejected.
     *
     * <p>{@code fields} is omitted entirely when empty rather than written as {@code []}: an absent
     * key reads as "no per-field detail", and an empty array invites the model to conclude that
     * nothing in particular was wrong with what it sent.
     *
     * @param fields one entry per rejected argument, each naming a key the model wrote and what is
     *     wrong with its value. The messages are the same ones a customer would be shown, so they
     *     are safe to paraphrase aloud — which is what the model will do with them
     */
    public static ObjectNode error(String code, String message, List<FieldError> fields) {
        ObjectNode node = object();
        node.put("error", code);
        node.put("message", message);
        if (!fields.isEmpty()) {
            ArrayNode entries = node.putArray("fields");
            for (FieldError field : fields) {
                ObjectNode entry = entries.addObject();
                entry.put("field", field.field());
                entry.put("message", field.message());
            }
        }
        return node;
    }
}
