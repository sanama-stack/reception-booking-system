package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * How a tool answers.
 *
 * <p>Two shapes and no third: an object of facts, or an object with an {@code error} and a
 * {@code message}. The model is told in the system prompt that an {@code error} means the thing did
 * not happen, which is the only interpretation available to it — there is no partial success in any
 * of the eight.
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
        ObjectNode node = object();
        node.put("error", code);
        node.put("message", message);
        return node;
    }
}
