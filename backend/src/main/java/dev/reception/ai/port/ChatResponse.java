package dev.reception.ai.port;

import java.util.List;
import java.util.Objects;

/**
 * What one model call returned: prose, tool calls, or both, plus what it cost.
 *
 * <p>The loop branches on {@link #hasToolCalls()} and nothing else. A response with no tool calls
 * ends the turn; a response with them runs them and goes round again.
 */
public record ChatResponse(String text, List<ToolCall> toolCalls, TokenUsage usage) {

    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.NONE : usage;
    }

    public static ChatResponse text(String text, TokenUsage usage) {
        return new ChatResponse(Objects.requireNonNull(text, "text"), List.of(), usage);
    }

    public static ChatResponse toolCalls(List<ToolCall> toolCalls, TokenUsage usage) {
        return new ChatResponse(null, toolCalls, usage);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
