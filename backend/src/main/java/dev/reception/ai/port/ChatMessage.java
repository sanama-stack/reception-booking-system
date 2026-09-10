package dev.reception.ai.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * One message in the list handed to a model.
 *
 * <p>Four shapes, one record, because that is what the wire format is — and because the
 * orchestration loop builds a heterogeneous list that has to stay in order. The static factories
 * are the only way to build one, so an assistant message carrying a {@code toolCallId} or a tool
 * result with no call to answer is not constructible.
 *
 * @param toolCalls empty on everything except an assistant turn that asked for tools
 * @param toolCallId set only on a {@code TOOL} message, naming the call it answers
 */
public record ChatMessage(ChatRole role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** The behavioural contract and the business's data. Rebuilt every turn, never stored. */
    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, Objects.requireNonNull(content, "content"), List.of(), null);
    }

    /** What the customer typed, inserted verbatim and never concatenated into the system prompt. */
    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, Objects.requireNonNull(content, "content"), List.of(), null);
    }

    /** A model turn that answered in prose. */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, List.of(), null);
    }

    /**
     * A model turn that asked for tools.
     *
     * <p>{@code content} may be null and usually is: a model that decided to call a tool typically
     * says nothing alongside it.
     */
    public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, toolCalls, null);
    }

    /**
     * What a tool returned, as JSON, quoting the call id it answers.
     *
     * <p>The result is serialised rather than described. A tool result summarised into prose would
     * make the model the last thing that read the real answer, which is the arrangement
     * ADR-0004 exists to avoid.
     */
    public static ChatMessage toolResult(String toolCallId, JsonNode result) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(result, "result");
        return new ChatMessage(ChatRole.TOOL, result.toString(), List.of(), toolCallId);
    }
}
