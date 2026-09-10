package dev.reception.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.reception.ai.port.ToolSpec;
import dev.reception.common.error.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The complete list of things the Receptionist can do, and the one place a tool failure becomes an
 * answer.
 *
 * <p>Built from every {@link Tool} bean Spring finds, so adding a ninth tool is adding a class —
 * and so is accidentally adding a ninth tool, which is why {@code ToolSchemaTest} asserts the count
 * and the names as well as the schemas.
 *
 * <p><strong>Failures are results, not exceptions.</strong> Both catch blocks below return JSON the
 * model can read and explain, because the alternative is a turn that dies on a slot someone else
 * took — an ordinary event that the customer should hear about as a sentence, not as a 500. The
 * distinction between them is who is expected to have gone wrong:
 *
 * <ul>
 *   <li>{@code ApiException} is the domain saying no. Its code and message are the ones every other
 *       surface uses, and the message is already written to be read by a customer, so it is passed
 *       through as-is.
 *   <li>Anything else is a defect. The model is told only that the tool failed; the stack trace goes
 *       to the log, where the conversation id ties it back to the transcript.
 * </ul>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** The generic failure the model sees for anything unexpected. Deliberately uninformative. */
    static final String TOOL_ERROR = "TOOL_ERROR";

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> discovered) {
        for (Tool tool : discovered) {
            Tool previous = tools.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("Two tools published the same name: " + tool.name());
            }
        }
    }

    /**
     * Every tool, on every call.
     *
     * <p>Not narrowed per turn. Choosing which tools to offer based on what the customer seems to
     * want is intent classification wearing a different hat, and ADR-0004 declined it: a wrong guess
     * removes a capability mid-conversation and the model has no way to ask for it back.
     */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** Whether a name the model produced is one of ours. */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public ObjectNode execute(String name, JsonNode arguments, ToolContext context) {
        Tool tool = tools.get(name);
        if (tool == null) {
            // Reachable only if a model invents a tool name, which strict mode makes very unlikely
            // and does not make impossible. An answer rather than a throw, for the same reason as
            // everything else here: the model can recover from being told the tool does not exist.
            log.warn("Model called an unknown tool: {} (conversation {})", name, context.conversationId());
            return ToolResults.error("UNKNOWN_TOOL", "That is not something I can do.");
        }

        try {
            return tool.execute(arguments, context);
        } catch (ApiException e) {
            log.info(
                    "Tool {} refused: {} (conversation {})",
                    name,
                    e.code(),
                    context.conversationId());
            // The fieldErrors travel with it. VALIDATION_FAILED's own message is "One or more
            // fields are invalid", which tells the model to try again and nothing about what to
            // change — and what it changed, three times running, was nothing.
            //
            // Measured by replaying the loop against the real tool schemas with create_appointment
            // refusing an unusable phone number: with the detail on the wire and rule 3 in the
            // prompt, the model asked the customer for a number in international form in 12 turns
            // out of 12, against 5 of 12 without it — the rest being a vague "there was a problem"
            // that leaves the customer with nothing to do. The incident's three identical retries
            // did not reproduce in a single-turn harness, so what is measured here is the answer the
            // customer gets rather than the calls that were saved.
            return ToolResults.error(e.code().name(), e.getMessage(), e.fieldErrors());
        } catch (RuntimeException e) {
            log.error("Tool {} failed unexpectedly (conversation {})", name, context.conversationId(), e);
            return ToolResults.error(TOOL_ERROR, "Something went wrong on my end. Let me try that another way.");
        }
    }
}
